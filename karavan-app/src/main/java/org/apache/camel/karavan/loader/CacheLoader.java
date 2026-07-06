package org.apache.camel.karavan.loader;

import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.model.*;
import org.apache.camel.karavan.persistence.AccessCacheEntity;
import org.apache.camel.karavan.persistence.PersistenceService;
import org.apache.camel.karavan.persistence.ProjectCacheEntity;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class CacheLoader {

    private final KaravanCache karavanCache;
    private final PersistenceService persistenceService;

    public void load() {
        log.info("Starting Karavan Cache Hydration...");

        var allEntities = persistenceService.getAll(ProjectCacheEntity.class);

        allEntities.stream()
                .filter(entity -> ProjectFolder.class.getSimpleName().equals(entity.type))
                .forEach(entity -> {
                    JsonObject json = new JsonObject(entity.data);
                    var project = json.mapTo(ProjectFolder.class);
                    karavanCache.saveProject(project, false);
                });

        allEntities.stream()
                .filter(entity -> ProjectFile.class.getSimpleName().equals(entity.type))
                .forEach(entity -> {
                    JsonObject json = new JsonObject(entity.data);
                    var file = json.mapTo(ProjectFile.class);
                    karavanCache.saveProjectFile(file, null, false);
                });

        // Commited (Source view) state: with per-project git there is no startup
        // re-import to rebuild it, so it hydrates from the DB like everything else.
        allEntities.stream()
                .filter(entity -> ProjectFolderCommited.class.getSimpleName().equals(entity.type))
                .forEach(entity -> {
                    JsonObject json = new JsonObject(entity.data);
                    karavanCache.saveProjectCommited(json.mapTo(ProjectFolderCommited.class), false);
                });

        allEntities.stream()
                .filter(entity -> ProjectFileCommited.class.getSimpleName().equals(entity.type))
                .forEach(entity -> {
                    JsonObject json = new JsonObject(entity.data);
                    karavanCache.saveProjectFileCommited(json.mapTo(ProjectFileCommited.class), false);
                });

        persistenceService.getAll(AccessCacheEntity.class).forEach(entity -> {
            JsonObject json = new JsonObject(entity.data);
            if (AccessUser.class.getSimpleName().equals(entity.type)) {
                karavanCache.saveUser(json.mapTo(AccessUser.class), false);
            } else if (AccessRole.class.getSimpleName().equals(entity.type)) {
                karavanCache.saveRole(json.mapTo(AccessRole.class), false);
            } else if (UserGitConfig.class.getSimpleName().equals(entity.type)) {
                karavanCache.saveUserGitConfig(json.mapTo(UserGitConfig.class), false);
            }
        });

        persistenceService.getActiveSessions().forEach(entity -> {
            JsonObject json = new JsonObject(entity.data);
            karavanCache.saveAccessSession(json.mapTo(AccessSession.class), false);
        });

        log.info("Karavan Cache Hydration complete.");
    }
}