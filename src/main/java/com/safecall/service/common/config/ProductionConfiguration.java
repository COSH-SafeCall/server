package com.safecall.service.common.config;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import com.safecall.service.common.crypto.FileUserKeyStore;
import com.safecall.service.common.crypto.UserKeyStore;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionConfiguration {
	@Bean
	static BeanFactoryPostProcessor requireProductionKeyStore(Environment environment) {
		// DB 접속이나 키 생성 전에 운영 환경의 잘못된 구성을 차단한다.
		return beanFactory -> {
			if (environment.acceptsProfiles(Profiles.of("local"))) {
				throw new IllegalStateException("Profiles local and prod must not be active together.");
			}
			String[] stores = beanFactory.getBeanNamesForType(UserKeyStore.class, true, false);
			if (stores.length != 1) {
				throw new IllegalStateException("Production requires exactly one external UserKeyStore. AWS key storage is not implemented yet.");
			}
			Class<?> type = beanFactory.getType(stores[0], false);
			if (type != null && FileUserKeyStore.class.isAssignableFrom(type)) {
				throw new IllegalStateException("FileUserKeyStore is restricted to local development.");
			}
		};
	}
}
