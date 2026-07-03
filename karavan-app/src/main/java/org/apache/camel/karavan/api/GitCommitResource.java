package org.apache.camel.karavan.api;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.model.ProjectFolder;
import org.apache.camel.karavan.model.ProjectFolderCommit;
import org.apache.camel.karavan.model.SystemCommit;
import org.apache.camel.karavan.service.GitHistoryService;
import org.apache.camel.karavan.service.GitService;

import java.util.List;
import java.util.Objects;

@Slf4j
@Path("/ui/git")
public class GitCommitResource extends AbstractApiResource {

    @Inject
    GitHistoryService gitHistoryService;

    @Inject
    GitService gitService;

    @GET
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/commits/{projectId}")
    public List<ProjectFolderCommit> getProjectCommits(@PathParam("projectId") String projectId) {
        try {
            return karavanCache.getProjectLastCommits(projectId);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return List.of();
        }
    }

    @POST
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/commits/{projectId}")
    public Response loadProjectCommits(@PathParam("projectId") String projectId) {
        try {
            ProjectFolder p = karavanCache.getProject(projectId);
            if (p == null || !gitService.hasRemote(p)) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Project has no Git repository configured").build();
            }
            String username = getIdentity().getString("username");
            if (p.getGitOwner() != null && !Objects.equals(p.getGitOwner(), username)) {
                return Response.status(Response.Status.FORBIDDEN).entity("Git remote is restricted to " + p.getGitOwner()).build();
            }
            gitHistoryService.importProjectCommits(p, karavanCache.getUserGitConfig(username));
            return Response.accepted().build();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @GET
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/system")
    public List<SystemCommit> getSystemCommits() {
        try {
            return karavanCache.getSystemLastCommits();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return List.of();
        }
    }
}