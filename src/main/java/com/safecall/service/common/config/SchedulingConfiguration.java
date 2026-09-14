package com.safecall.service.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** External cleanup must never occupy the scheduler that dispatches calls and expiry checks. */
@Configuration(proxyBeanMethods=false)
public class SchedulingConfiguration {
	@Bean public ThreadPoolTaskScheduler callScheduler(){return scheduler("call-scheduler-",1);}
	@Bean public ThreadPoolTaskScheduler cleanupScheduler(){return scheduler("cleanup-scheduler-",2);}
	private ThreadPoolTaskScheduler scheduler(String prefix,int size){
		var scheduler=new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(size);scheduler.setThreadNamePrefix(prefix);
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		return scheduler;
	}
}
