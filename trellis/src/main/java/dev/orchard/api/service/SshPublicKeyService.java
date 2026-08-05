package dev.orchard.api.service;

import dev.orchard.core.model.SshPublicKey;
import dev.orchard.roots.entity.SshPublicKeyEntity;
import dev.orchard.roots.repository.SshPublicKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SshPublicKeyService {

    private final SshPublicKeyRepository repository;
    private final CultivatorService cultivatorService;

    public SshPublicKeyService(SshPublicKeyRepository repository, CultivatorService cultivatorService) {
        this.repository = repository;
        this.cultivatorService = cultivatorService;
    }

    @Transactional
    public SshPublicKey register(UUID cultivatorId, String name, String publicKey) {
        cultivatorService.ensureCultivator(cultivatorId);
        SshPublicKey key = SshPublicKey.register(cultivatorId, name, publicKey);
        repository.save(SshPublicKeyEntity.fromModel(key));
        return key;
    }

    public List<SshPublicKey> listForCultivator(UUID cultivatorId) {
        return repository.findByCultivatorId(cultivatorId).stream()
            .map(SshPublicKeyEntity::toModel)
            .toList();
    }

    @Transactional
    public boolean delete(UUID cultivatorId, UUID keyId) {
        if (repository.findByIdAndCultivatorId(keyId, cultivatorId).isEmpty()) {
            return false;
        }
        repository.deleteByIdAndCultivatorId(keyId, cultivatorId);
        return true;
    }
}
