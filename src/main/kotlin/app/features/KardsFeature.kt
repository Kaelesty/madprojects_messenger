package app.features

import domain.KanbanRepository
import domain.project.ProjectRepo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import shared_domain.entities.KanbanState

interface KardsFeature {

    suspend fun getProjectKards(rc: RoutingContext)
}

class KardsFeatureImpl(
    private val kanbanRepository: KanbanRepository,
    private val projectRepo: ProjectRepo
): KardsFeature {

    override suspend fun getProjectKards(rc: RoutingContext) {
        with(rc) {
            val principal = call.principal<JWTPrincipal>()
            val userId = principal!!.payload.getClaim("userId").asString()
            val projectId = call.parameters["projectId"]
            if (projectId == null || !projectRepo.checkUserInProject(userId, projectId)) {
                call.respond(HttpStatusCode.NotFound)
                return
            }
            val kanban = kanbanRepository.getKanban(projectId.toInt())
            val kards = mutableListOf<KanbanState.Kard>()
            kanban.columns.forEach {
                it.kards.forEach {
                    kards.add(it)
                }
            }

            call.respondText(
                text = Json.encodeToString(kards),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        }
    }
}