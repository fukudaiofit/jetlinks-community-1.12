package org.jetlinks.community.rule.engine.device;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.PropertyConstants;
import org.jetlinks.community.ValueObject;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.event.Subscription;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.metadata.Jsonable;
import org.jetlinks.core.utils.FluxUtils;
import org.jetlinks.reactor.ql.ReactorQL;
import org.jetlinks.reactor.ql.ReactorQLContext;
import org.jetlinks.reactor.ql.ReactorQLRecord;
import org.jetlinks.reactor.ql.utils.CastUtils;
import org.jetlinks.rule.engine.api.RuleConstants;
import org.jetlinks.rule.engine.api.RuleData;
import org.jetlinks.rule.engine.api.task.ExecutionContext;
import org.jetlinks.rule.engine.api.task.Task;
import org.jetlinks.rule.engine.api.task.TaskExecutor;
import org.jetlinks.rule.engine.api.task.TaskExecutorProvider;
import org.jetlinks.rule.engine.defaults.AbstractTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@AllArgsConstructor
@Component
public class DeviceAlarmTaskExecutorProvider implements TaskExecutorProvider {

    private final EventBus eventBus;

    private final Scheduler scheduler;

    @Override
    public String getExecutor() {
        return "device_alarm";
    }

    @Override
    public Mono<TaskExecutor> createTask(ExecutionContext context) {
        return Mono.just(new DeviceAlarmTaskExecutor(context, eventBus, scheduler));
    }

    static class DeviceAlarmTaskExecutor extends AbstractTaskExecutor {

        /**
         * ?????????????????????
         */
        static List<String> default_columns = Arrays.asList(
            //?????????
            "this.timestamp timestamp",
            //??????ID
            "this.deviceId deviceId",
            //header
            "this.headers headers",
            //????????????,??????DeviceMessageConnector??????????????????
            "this.headers.deviceName deviceName",
            //????????????ID
            "this.headers._uid _uid",
            //????????????,??????????????????????????????????????????,??????:?????????,??????????????????????????????????????????.
            "this.messageType messageType"
        );
        private final EventBus eventBus;

        private final Scheduler scheduler;

        //??????????????????ReactorQL??????
        private final Map<DeviceAlarmRule.Trigger, ReactorQL> triggerQL = new ConcurrentHashMap<>();

        //????????????
        private DeviceAlarmRule rule;

        DeviceAlarmTaskExecutor(ExecutionContext context,
                                EventBus eventBus,
                                Scheduler scheduler) {
            super(context);
            this.eventBus = eventBus;
            this.scheduler = scheduler;
            init();
        }


        @Override
        public String getName() {
            return "????????????";
        }

        @Override
        protected Disposable doStart() {
            rule.validate();
            return doSubscribe(eventBus)
                .filter(ignore -> state == Task.State.running)
                .flatMap(result -> {
                    RuleData data = context.newRuleData(result);
                    //?????????????????????
                    return context
                        .getOutput()
                        .write(Mono.just(data))
                        .then(context.fireEvent(RuleConstants.Event.result, data));
                })
                .onErrorResume(err -> context.onError(err, null))
                .subscribe();
        }

        void init() {
            rule = createRule();
            Map<DeviceAlarmRule.Trigger, ReactorQL> ql = createQL(rule);
            triggerQL.clear();
            triggerQL.putAll(ql);
        }

        @Override
        public void reload() {
            init();
            if (disposable != null) {
                disposable.dispose();
            }
            disposable = doStart();
        }

        @Nonnull
        private DeviceAlarmRule createRule() {
            DeviceAlarmRule rule = ValueObject
                .of(context.getJob().getConfiguration())
                .get("rule")
                .map(val -> FastBeanCopier.copy(val, new DeviceAlarmRule()))
                .orElseThrow(() -> new IllegalArgumentException("error.alarm_configuration_error"));
            rule.validate();
            return rule;
        }

        @Override
        public void validate() {
            try {
                createQL(createRule());
            } catch (Exception e) {
                throw new BusinessException("error.configuration_error", 500, e.getMessage(), e);
            }
        }

        static ReactorQL createQL(int index, DeviceAlarmRule.Trigger trigger, DeviceAlarmRule rule) {
            String sql = trigger.toSQL(index, default_columns, rule.getProperties());
            log.debug("create device alarm sql : \n{}", sql);
            return ReactorQL.builder().sql(sql).build();
        }

        private Map<DeviceAlarmRule.Trigger, ReactorQL> createQL(DeviceAlarmRule rule) {
            Map<DeviceAlarmRule.Trigger, ReactorQL> qlMap = new HashMap<>();
            int index = 0;
            for (DeviceAlarmRule.Trigger trigger : rule.getTriggers()) {
                qlMap.put(trigger, createQL(index++, trigger, rule));
            }
            return qlMap;
        }

        public Flux<Map<String, Object>> doSubscribe(EventBus eventBus) {

            //????????????????????????????????????
            List<Flux<? extends Map<String, Object>>> triggerOutputs = new ArrayList<>();

            int index = 0;

            //?????????????????????
            //???????????????: ???????????????????????????????????????,???????????????????????????????????????
            Flux<RuleData> input = context
                .getInput()
                .accept()
                //??????cache,?????????????????????????????????
                //??????header????????????????????????????????????????????????,???????????????????????????.
                .cache(0);

            for (DeviceAlarmRule.Trigger trigger : rule.getTriggers()) {
                //QL?????????,?????????????????????
                ReactorQL ql = triggerQL.get(trigger);
                if (ql == null) {
                    log.warn("DeviceAlarmRule trigger {} init error", index);
                    continue;
                }
                Flux<? extends Map<String, Object>> datasource;

                int currentIndex = index;
                //since 1.11 ?????????????????????eventBus??????
                if (trigger.getTrigger() == DeviceAlarmRule.TriggerType.timer) {
                    //?????????????????????????????????(???????????????????????????????????????????????????)
                    datasource = input
                        .filter(data -> {
                            //?????????????????????header????????????????????????????????????????????????????????????????
                            return data
                                .getHeader("triggerIndex")
                                .map(idx -> CastUtils.castNumber(idx).intValue() == currentIndex)
                                .orElse(true);
                        })
                        .flatMap(RuleData::dataToMap);
                }
                //??????????????????????????????
                else {
                    String topic = trigger
                        .getType()
                        .getTopic(rule.getProductId(), rule.getDeviceId(), trigger.getModelId());

                    //???????????????????????????????????????
                    Subscription subscription = Subscription.of(
                        "device_alarm:" + rule.getId() + ":" + index++,
                        topic,
                        Subscription.Feature.local
                    );
                    datasource = eventBus
                        .subscribe(subscription, DeviceMessage.class)
                        .map(Jsonable::toJson);

                }

                ReactorQLContext qlContext = ReactorQLContext
                    .ofDatasource((t) -> datasource
                        .doOnNext(map -> {
                            if (StringUtils.hasText(rule.getDeviceName())) {
                                map.putIfAbsent("deviceName", rule.getDeviceName());
                            }
                            if (StringUtils.hasText(rule.getProductName())) {
                                map.putIfAbsent("productName", rule.getProductName());
                            }
                            map.put("productId", rule.getProductId());
                            map.put("alarmId", rule.getId());
                            map.put("alarmName", rule.getName());
                        }));
                //??????SQL?????????????????????
                trigger.toFilterBinds().forEach(qlContext::bind);

                //??????ReactorQL????????????????????????
                triggerOutputs.add(ql.start(qlContext).map(ReactorQLRecord::asMap));
            }

            Flux<Map<String, Object>> resultFlux = Flux.merge(triggerOutputs);

            //??????
            ShakeLimit shakeLimit;
            if ((shakeLimit = rule.getShakeLimit()) != null) {

                resultFlux = shakeLimit.transfer(
                    resultFlux,
                    (duration, flux) ->
                        StringUtils.hasText(rule.getDeviceId())
                            //????????????????????????????????????,??????????????????????????????
                            ? flux.window(duration, scheduler)
                            //??????????????????????????????,????????????ID?????????????????????
                            //????????????,?????????????????????
                            : flux
                            .groupBy(map -> String.valueOf(map.get("deviceId")), Integer.MAX_VALUE)
                            .flatMap(group -> group.window(duration, scheduler), Integer.MAX_VALUE),
                    (alarm, total) -> alarm.put("totalAlarms", total)
                );
            }

            return resultFlux
                .as(result -> {
                    //??????????????????????????????????????????????????????,
                    //??????????????????????????????????????????????????????
                    if (rule.getTriggers().size() > 1) {
                        return result
                            .as(FluxUtils.distinct(
                                map -> map.getOrDefault(PropertyConstants.uid.getKey(), ""),
                                Duration.ofSeconds(1)));
                    }
                    return result;
                })
                .flatMap(map -> {
                    @SuppressWarnings("all")
                    Map<String, Object> headers = (Map<String, Object>) map.remove("headers");
                    map.put("productId", rule.getProductId());
                    map.put("alarmId", rule.getId());
                    map.put("alarmName", rule.getName());
                    if (null != rule.getLevel()) {
                        map.put("alarmLevel", rule.getLevel());
                    }
                    if (null != rule.getType()) {
                        map.put("alarmType", rule.getType());
                    }
                    if (StringUtils.hasText(rule.getDeviceName())) {
                        map.putIfAbsent("deviceName", rule.getDeviceName());
                    }
                    if (StringUtils.hasText(rule.getProductName())) {
                        map.putIfAbsent("productName", rule.getProductName());
                    }
                    if (StringUtils.hasText(rule.getDeviceId())) {
                        map.putIfAbsent("deviceId", rule.getDeviceId());
                    }
                    if (!map.containsKey("deviceName") && map.get("deviceId") != null) {
                        map.putIfAbsent("deviceName", map.get("deviceId"));
                    }
                    if (!map.containsKey("productName")) {
                        map.putIfAbsent("productName", rule.getProductId());
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("??????????????????:{}", map);
                    }

                    //???????????????????????????ID???????????????????????????
                    map.putIfAbsent("id", IDGenerator.MD5.generate());
                    return eventBus
                        .publish(String.format(
                            "/rule-engine/device/alarm/%s/%s/%s",
                            rule.getProductId(),
                            map.get("deviceId"),
                            rule.getId()), map)
                        .thenReturn(map);

                });
        }
    }

}
