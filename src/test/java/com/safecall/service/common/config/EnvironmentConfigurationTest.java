package com.safecall.service.common.config;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.safecall.service.common.crypto.FileUserKeyStore;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.crypto.UserKeyStore;

class EnvironmentConfigurationTest {
	@TempDir Path keys;
	private ApplicationContextRunner configuration() {
		return new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("spring.config.location=classpath:/",
				"spring.profiles.active=", "DB_HOST=synthetic-db.example", "DB_USERNAME=synthetic-user",
				"DB_PASSWORD=synthetic-password", "SWAGGER_ENABLED=true",
				"app.jwt.secret=synthetic-independent-signing-key-at-least-32-bytes",
				"app.crypto.hmac-secret=" + Base64.getEncoder().encodeToString(new byte[32]),
				"app.crypto.response-secret=" + Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"app.crypto.key-directory=" + keys);
	}
	private ApplicationContextRunner application() {
		return configuration().withUserConfiguration(FileUserKeyStore.class, SecretCrypto.class, ProductionConfiguration.class);
	}
	@Test void defaultLocalProfileKeepsKeysAcrossContextRestarts() {
		UUID userId = UUID.randomUUID();
		byte[][] original = new byte[1][];
		application().run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(FileUserKeyStore.class);
			UserKeyStore store = context.getBean(UserKeyStore.class);
			original[0] = store.read(store.create(userId));
			assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("true");
		});
		application().run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(UserKeyStore.class).read(userId.toString())).isEqualTo(original[0]);
		});
	}
	@Test void missingLocalKeyIsNotSilentlyRegenerated() {
		application().run(context -> {
			assertThatThrownBy(() -> context.getBean(UserKeyStore.class).read(UUID.randomUUID().toString()))
				.isInstanceOf(IllegalStateException.class).hasMessage("User key unavailable.");
			assertThat(keys.toFile().list()).isEmpty();
		});
	}
	@Test void localSwaggerCanStillBeDisabled() {
		application().withPropertyValues("spring.profiles.active=local", "SWAGGER_ENABLED=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
		});
	}
	@Test void productionConfigurationDoesNotImportLocalSecretsOrEnableSwagger() {
		configuration().withPropertyValues("spring.profiles.active=prod").run(context -> {
			assertThat(context).hasNotFailed();
			var environment = context.getEnvironment();
			assertThat(environment.getPropertySources()).noneMatch(source -> source.getName().contains(".env"));
			assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
			assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
			assertThat(environment.getProperty("spring.datasource.url"))
				.startsWith("jdbc:mysql://synthetic-db.example:").contains("sslMode=VERIFY_IDENTITY");
		});
	}
	@Test void productionDoesNotSelectFileStore() {
		configuration().withUserConfiguration(FileUserKeyStore.class, SecretCrypto.class)
			.withPropertyValues("spring.profiles.active=prod").run(context -> {
				assertThat(context).hasNotFailed().doesNotHaveBean(UserKeyStore.class);
			});
	}
	@Test void productionStartupFailsUntilExternalKeyStoreIsImplemented() {
		application().withPropertyValues("spring.profiles.active=prod").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasMessageContaining("Production requires exactly one external UserKeyStore");
		});
	}
	@Test void mixedLocalAndProductionProfilesAreRejected() {
		application().withPropertyValues("spring.profiles.active=local,prod").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasMessageContaining("Profiles local and prod must not be active together");
		});
	}
}
