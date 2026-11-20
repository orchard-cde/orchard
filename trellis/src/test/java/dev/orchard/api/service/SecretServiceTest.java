package dev.orchard.api.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.orchard.api.dto.SecretWarning;
import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.DevcontainerSeed.SecretDeclaration;
import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.Secret;
import dev.orchard.knothole.SecretVault;
import dev.orchard.roots.entity.SecretEntity;
import dev.orchard.roots.repository.SecretRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SecretServiceTest {

    static final class FailingVault implements SecretVault {
        final Map<String, byte[]> entries = new HashMap<>();
        boolean failNextPut;
        boolean failNextDelete;
        int purgeResult;

        @Override
        public void put(String key, byte[] value) {
            if (failNextPut) {
                failNextPut = false;
                throw new IllegalStateException("put failed");
            }
            entries.put(key, value.clone());
        }

        @Override
        public Optional<byte[]> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public boolean delete(String key) {
            if (failNextDelete) {
                failNextDelete = false;
                throw new IllegalStateException("delete failed");
            }
            return entries.remove(key) != null;
        }

        @Override
        public Set<String> keys() {
            return Set.copyOf(entries.keySet());
        }

        @Override
        public int purge() {
            return purgeResult;
        }
    }

    @Mock private SecretRepository repository;
    @Mock private CultivatorService cultivatorService;
    @Mock private PlatformTransactionManager transactionManager;

    private final FailingVault vault = new FailingVault();
    private final Map<String, SecretEntity> rows = new LinkedHashMap<>();
    private final UUID cultivatorId = UUID.randomUUID();
    private SecretService service;

    @BeforeEach
    void setUp() {
        service = new SecretService(repository, vault, cultivatorService, new TransactionTemplate(transactionManager));
        lenient().when(repository.findByCultivatorIdAndName(any(), anyString()))
            .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(1))));
        lenient().when(repository.saveAndFlush(any(SecretEntity.class))).thenAnswer(inv -> {
            SecretEntity entity = inv.getArgument(0);
            rows.put(entity.getName(), entity);
            return entity;
        });
        lenient().when(repository.findAllVaultKeys())
            .thenAnswer(inv -> rows.values().stream().map(SecretEntity::getVaultKey).toList());
        lenient().when(repository.findByCultivatorId(any()))
            .thenAnswer(inv -> new ArrayList<>(rows.values()));
        lenient().when(repository.findByCultivatorIdAndNameIn(any(), anyCollection())).thenAnswer(inv -> {
            Collection<String> names = inv.getArgument(1);
            return rows.values().stream().filter(e -> names.contains(e.getName())).toList();
        });
        lenient().doAnswer(inv -> {
            SecretEntity entity = inv.getArgument(0);
            rows.remove(entity.getName());
            return null;
        }).when(repository).delete(any(SecretEntity.class));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void put_new_writesEntryThenRow_rotatedEqualsCreated() {
        SecretService.Stored stored = service.put(cultivatorId, "API_TOKEN", bytes("v1"));

        assertThat(stored.created()).isTrue();
        Secret secret = stored.secret();
        assertThat(secret.rotatedAt()).isEqualTo(secret.createdAt());
        assertThat(vault.keys()).containsExactly(secret.vaultKey());
        assertThat(vault.get(secret.vaultKey())).hasValueSatisfying(v -> assertThat(v).isEqualTo(bytes("v1")));
        assertThat(rows).containsOnlyKeys("API_TOKEN");
        verify(cultivatorService).ensureCultivator(cultivatorId);
    }

    @Test
    void put_overwrite_newKey_oldEntryDeleted_rotatedBumped() throws Exception {
        Secret first = service.put(cultivatorId, "API_TOKEN", bytes("v1")).secret();
        Thread.sleep(5);

        SecretService.Stored second = service.put(cultivatorId, "API_TOKEN", bytes("v2"));

        assertThat(second.created()).isFalse();
        assertThat(second.secret().vaultKey()).isNotEqualTo(first.vaultKey());
        assertThat(vault.keys()).containsExactly(second.secret().vaultKey());
        assertThat(second.secret().rotatedAt()).isAfter(first.rotatedAt());
        assertThat(second.secret().createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void put_vaultWriteFails_noRowChange() {
        vault.failNextPut = true;

        assertThatThrownBy(() -> service.put(cultivatorId, "API_TOKEN", bytes("v1")))
            .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).saveAndFlush(any());
        assertThat(rows).isEmpty();
    }

    @Test
    void put_commitFails_newEntryRemoved_oldValueStillResolves() {
        Secret first = service.put(cultivatorId, "API_TOKEN", bytes("old")).secret();
        doThrow(new IllegalStateException("db down")).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.put(cultivatorId, "API_TOKEN", bytes("new")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(vault.keys()).containsExactly(first.vaultKey());
        assertThat(service.resolve(cultivatorId, List.of("API_TOKEN")).get("API_TOKEN")).isEqualTo(bytes("old"));
    }

    @Test
    void put_concurrentFirstWrite_retriesAsOverwrite() {
        Secret existing = service.put(cultivatorId, "API_TOKEN", bytes("racer")).secret();
        AtomicInteger finds = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        lenient().when(repository.findByCultivatorIdAndName(any(), eq("API_TOKEN"))).thenAnswer(inv ->
            finds.getAndIncrement() == 0 ? Optional.empty() : Optional.ofNullable(rows.get("API_TOKEN")));
        doAnswer(inv -> {
            if (saves.getAndIncrement() == 0) {
                throw new DataIntegrityViolationException("uq_secrets_cultivator_name");
            }
            SecretEntity entity = inv.getArgument(0);
            rows.put(entity.getName(), entity);
            return entity;
        }).when(repository).saveAndFlush(any());

        SecretService.Stored stored = service.put(cultivatorId, "API_TOKEN", bytes("mine"));

        assertThat(stored.created()).isFalse();
        assertThat(rows).hasSize(1);
        assertThat(rows.get("API_TOKEN").getVaultKey()).isEqualTo(stored.secret().vaultKey());
        assertThat(stored.secret().vaultKey()).isNotEqualTo(existing.vaultKey());
        assertThat(vault.keys()).containsExactly(stored.secret().vaultKey());
    }

    @Test
    void delete_entryDeleteFails_rowGone_sweepsAutomatically() {
        Secret secret = service.put(cultivatorId, "API_TOKEN", bytes("v1")).secret();
        vault.failNextDelete = true;

        boolean deleted = service.delete(cultivatorId, "API_TOKEN");

        assertThat(deleted).isTrue();
        assertThat(rows).isEmpty();
        assertThat(vault.keys()).doesNotContain(secret.vaultKey()).isEmpty();
    }

    @Test
    void delete_unknownName_returnsFalse() {
        assertThat(service.delete(cultivatorId, "NOPE")).isFalse();
    }

    @Test
    void put_oldEntryDeleteFails_sweepsAutomatically() {
        service.put(cultivatorId, "API_TOKEN", bytes("v1"));
        vault.failNextDelete = true;

        Secret second = service.put(cultivatorId, "API_TOKEN", bytes("v2")).secret();

        assertThat(vault.keys()).containsExactly(second.vaultKey());
    }

    @Test
    void onReady_sweepsOrphans() {
        vault.put("orphan-key", bytes("x"));

        service.onReady();

        assertThat(vault.keys()).isEmpty();
    }

    @Test
    void sweep_includesPurge() {
        vault.put("orphan-key", bytes("x"));
        vault.purgeResult = 2;

        assertThat(service.sweep()).isEqualTo(3);
    }

    @Test
    void put_rejectsNul_rejectsOver64KiB_rejectsBadName() {
        assertThatThrownBy(() -> service.put(cultivatorId, "OK", new byte[] {'a', 0, 'b'}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(cultivatorId, "OK", new byte[65537]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(cultivatorId, "1bad-name", bytes("v")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(vault.keys()).isEmpty();
    }

    @Test
    void put_allowsEmptyAndExactlyMaxSize() {
        assertThat(service.put(cultivatorId, "EMPTY", new byte[0]).created()).isTrue();
        assertThat(service.put(cultivatorId, "BIG", "a".repeat(65536).getBytes(StandardCharsets.UTF_8)).created()).isTrue();
    }

    @Test
    void put_neverLogsValue() {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.orchard");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String value = "s3cr3t-value-" + UUID.randomUUID();
            service.put(cultivatorId, "API_TOKEN", bytes(value));
            service.put(cultivatorId, "API_TOKEN", bytes(value + "-2"));
            vault.failNextDelete = true;
            service.delete(cultivatorId, "API_TOKEN");
            doThrow(new IllegalStateException("db down")).when(repository).saveAndFlush(any());
            assertThatThrownBy(() -> service.put(cultivatorId, "OTHER", bytes(value + "-3")))
                .isInstanceOf(IllegalStateException.class);

            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(value));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void resolve_returnsOnlyStoredNames() {
        service.put(cultivatorId, "A", bytes("1"));
        service.put(cultivatorId, "B", bytes("2"));

        Map<String, byte[]> resolved = service.resolve(cultivatorId, List.of("A", "MISSING"));

        assertThat(resolved).containsOnlyKeys("A");
        assertThat(resolved.get("A")).isEqualTo(bytes("1"));
    }

    @Test
    void resolve_rowWithoutEntry_throwsIllegalState() {
        Secret secret = service.put(cultivatorId, "A", bytes("1")).secret();
        vault.entries.remove(secret.vaultKey());

        assertThatThrownBy(() -> service.resolve(cultivatorId, List.of("A")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("A");
    }

    @Test
    void list_returnsModels() {
        service.put(cultivatorId, "A", bytes("1"));

        assertThat(service.list(cultivatorId)).extracting(Secret::name).containsExactly("A");
    }

    @Test
    void missingFor_devcontainerSeed_listsUndeclaredNamesWithDescriptionAndUrl() {
        service.put(cultivatorId, "HAVE", bytes("1"));
        Map<String, SecretDeclaration> declared = new LinkedHashMap<>();
        declared.put("MISSING_ONE", new SecretDeclaration("first", "https://docs.example/one"));
        declared.put("HAVE", new SecretDeclaration("present", null));
        declared.put("MISSING_TWO", new SecretDeclaration(null, null));
        DevcontainerSeed seed = DevcontainerSeed.builder().name("n").image("img").secrets(declared).build();

        List<SecretWarning> warnings = service.missingFor(cultivatorId, seed);

        assertThat(warnings).containsExactly(
            new SecretWarning("MISSING_ONE", "first", "https://docs.example/one"),
            new SecretWarning("MISSING_TWO", null, null));
    }

    @Test
    void missingFor_devfileSeed_empty() {
        assertThat(service.missingFor(cultivatorId, mock(DevfileSeed.class))).isEmpty();
    }
}
