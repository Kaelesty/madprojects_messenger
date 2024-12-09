package app

import domain.GithubTokensRepo
import domain.IntegrationService
import domain.KanbanRepository
import domain.RepositoriesRepo
import domain.UnreadMessagesRepository
import entities.*
import entities.Action.Kanban.*
import entities.Action.Messenger.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import shared_domain.repos.ChatsRepository
import shared_domain.repos.MessagesRepository
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import shared_domain.entities.Branch
import shared_domain.entities.BranchCommit
import shared_domain.entities.BranchCommitView
import shared_domain.entities.BranchCommits
import shared_domain.entities.GithubTokens
import shared_domain.entities.GithubUser
import shared_domain.entities.RepoBranchView
import shared_domain.entities.RepoView
import java.io.File
import java.lang.Thread.sleep
import java.security.KeyStore

class Application : KoinComponent {

    private val githubAuthLink =
        "https://github.com/login/oauth/access_token?client_id=${Config.Github.clientId}&client_secret=${Config.Github.clientSecret}"
    private val githubRepoLink = "https://api.github.com/repos"

    data class Context(
        val user: User,
        val projectSessions: List<ProjectSession>
    )

    data class ProjectSession(
        val id: Int,
        val projectBackFlow: ProjectBackFlowManager.ProjectBackFlow.BackFlow,
        val observeKanban: Boolean,
        val observeMessenger: Boolean,
    )

    class ActionHolder(
        val action: Action,
        val projectId: Int,
    )

    private val chatsRepo: ChatsRepository by inject()
    private val messagesRepo: MessagesRepository by inject()
    private val integrationRepo: IntegrationService by inject()
    private val unreadMessagesRepo: UnreadMessagesRepository by inject()
    private val kanbanRepository: KanbanRepository by inject()
    private val githubTokensRepo: GithubTokensRepo by inject()
    private val repositoriesRepo: RepositoriesRepo by inject()

    private val httpClient: HttpClient by inject()

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    init {
        setup()
    }

    fun run() {
        server.start(wait = true)
    }

    private fun setup() {
        server = embeddedServer(
            Netty,
            environment = applicationEnvironment {
            },
            {
                val keyStoreFile = File("src/main/resources/keystore.p12")

                connector {
                    port = 8079
                }

                sslConnector(
                    keyStore = KeyStore.getInstance("PKCS12").apply {
                        load(
                            File("src/main/resources/keystore.p12").inputStream(),
                            Config.SslConfig.password.toString().toCharArray()
                        )
                    },
                    keyAlias = "sampleAlias",
                    keyStorePassword = { Config.SslConfig.password.toString().toCharArray() },
                    privateKeyPassword = { Config.SslConfig.password.toString().toCharArray() }
                ) {
                    port = 8080
                    keyStorePath = keyStoreFile
                }
            }
        ) {
            install(WebSockets)

            install(CORS) {
                anyHost()
                allowHeader("code")
                allowHeader("state")
                allowHeader("repolink")
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Patch)
                allowHeader(HttpHeaders.AccessControlAllowHeaders)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.AccessControlAllowOrigin)
                allowCredentials = true
            }

            routing {

                get("/dbg") {
                    call.respond(HttpStatusCode.OK, "ouch")
                }

                get("/getRepoBranchContent") {
                    val jwt = call.parameters["jwt"]
                    val sha = call.parameters["sha"]
                    val repo = call.parameters["repoName"]
                    if (sha == null || jwt == null || repo == null) {
                        call.respond(HttpStatusCode.BadRequest, "Bad parameters")
                        return@get
                    }
                    val userId = integrationRepo.getUserFromJWT(jwt)
                    val githubJwt = getAccessToken(userId.id)

                    if (githubJwt == null) {
                        call.respond(HttpStatusCode.TooEarly)
                        return@get
                    }

                    val response = httpClient.get(
                        "$githubRepoLink/$repo/commits?per_page=1000&sha=$sha"
                    )  {
                        header("Authorization", "Bearer $githubJwt")
                    }
                    if (response.status == HttpStatusCode.OK) {
                        try {
                            val body = response.body<List<BranchCommit>>()
                            val commits = body.map {
                                BranchCommitView(
                                    sha = it.sha,
                                    authorGithubId = it.author.id,
                                    date = it.data.author.date,
                                    message = it.data.message
                                )
                            }

                            val authorIds = commits.map { it.authorGithubId }
                            val authors = authorIds.distinct().map { githubUserId ->
                                githubTokensRepo.getUserMeta(githubUserId)
                            }

                            call.respond(
                                HttpStatusCode.OK,
                                Json.encodeToString(
                                    BranchCommits(
                                        commits = commits,
                                        authors = authors.filterNotNull()
                                    )
                                )
                            )

                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.Conflict)
                        }
                    }
                    else {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                    }
                }

                get("/getProjectRepoBranches") {
                    val jwt = call.parameters["jwt"]
                    val projectId = call.parameters["projectId"]
                    if (projectId == null || jwt == null) {
                        call.respond(HttpStatusCode.BadRequest, "Bad parameters")
                        return@get
                    }
                    val userId = integrationRepo.getUserFromJWT(jwt)
                    val githubJwt = getAccessToken(userId.id)

                    if (githubJwt == null) {
                        call.respond(HttpStatusCode.TooEarly)
                        return@get
                    }

                    val projectRepos = repositoriesRepo.getProjectRepos(projectId)
                    val repos = mutableListOf<RepoView>()

                    projectRepos.forEach {
                        val repoBranches = mutableListOf<RepoBranchView>()
                        val parts = it.link.split("/").reversed()
                        val response = httpClient.get("$githubRepoLink/${parts[1]}/${parts[0]}/branches")  {
                            header("Authorization", "Bearer $githubJwt")
                        }
                        if (response.status == HttpStatusCode.OK) {
                            try {
                                val body = response.body<List<Branch>>()
                                body.forEach { branch ->
                                    repoBranches.add(
                                        RepoBranchView(
                                            name = "${parts[0]}/${branch.name}",
                                            sha = branch.data.sha,
                                        )
                                    )
                                }

                                repos.add(
                                    RepoView(
                                        name = "${parts[1]}/${parts[0]}",
                                        repoBranches
                                    )
                                )
                            } catch (e: Exception) {
                                e
                                call.respond(HttpStatusCode.Conflict)
                            }
                        } else {
                            call.respond(HttpStatusCode.ServiceUnavailable)
                        }
                    }

                    call.respond(
                        HttpStatusCode.OK, Json.encodeToString(
                            repos
                        )
                    )
                }

                get("/verifyRepoLink") {
                    val jwt = call.parameters["jwt"]
                    val link = call.parameters["repolink"]
                    if (link == null || jwt == null) {
                        call.respond(HttpStatusCode.BadRequest, "Bad parameters")
                        return@get
                    }
                    val userId = integrationRepo.getUserFromJWT(jwt)
                    val githubJwt = githubTokensRepo.getAccessToken(userId.id)

                    if (githubJwt == null) {
                        call.respond(HttpStatusCode.TooEarly)
                        return@get
                    }

                    val parts = link.split("/").reversed()
                    val response = httpClient.get("$githubRepoLink/${parts[1]}/${parts[0]}") {
                        header("Authentication", "Bearer $githubJwt")
                    }
                    if (response.status == HttpStatusCode.OK) {
                        call.respond(HttpStatusCode.OK)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Invalid repolink")
                    }

                }

                get("/githubCallbackUrl") {
                    val githubCode = call.parameters["code"] ?: call.respond(
                        HttpStatusCode.Unauthorized,
                        "Failed to parse github code"
                    )
                    val userId = call.parameters["state"]
                        ?.let {
                            integrationRepo.getUserFromJWT(jwt = it)
                        }?.id

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, "Failed to get tokens from code")
                        return@get
                    }

                    val response = httpClient.get(githubAuthLink + "&code=$githubCode")
                    if (response.status == HttpStatusCode.OK) {
                        val body = response.body<GithubTokens>()
                        //val (accessToken, refreshToken) = extractTokens(body)

                        if (body.refresh_token == null || body.access_token == null) {
                            call.respond(HttpStatusCode.Unauthorized, "Failed to parse tokens")
                            return@get
                        }

                        val metaResponse = httpClient.get("https://api.github.com/user") {
                            header("Authorization", "Bearer ${body.access_token}")
                        }
                        val body2 = metaResponse.body<String>()
                        body2
                        if (metaResponse.status != HttpStatusCode.OK) {
                            call.respond(HttpStatusCode.FailedDependency, "Failed to load user meta via secondary request")
                        }

                        try {
                            val githubUser = metaResponse.body<GithubUser>()
                            githubTokensRepo.save(
                                access = body.access_token,
                                refresh = body.refresh_token,
                                userId = userId,
                                githubId = githubUser.id,
                                avatar = githubUser.avatarUrl,
                                profileLink = githubUser.profileLink
                            )
                        } catch (e: Exception) {
                            e
                            call.respond(HttpStatusCode.FailedDependency, "Failed to parse user meta from secondary request")
                        }


                        call.respond(HttpStatusCode.OK, "Tokens are recorded")
                    } else call.respond(HttpStatusCode.Unauthorized, "Failed to get tokens from code")
                }

                webSocket("/project") {


                    val localScope = CoroutineScope(Dispatchers.IO)
                    var session: MutableStateFlow<Context?> = MutableStateFlow(null)
                    var backFlow: ProjectBackFlowManager.ProjectBackFlow.BackFlow? = null
                    val localBackFlow = MutableSharedFlow<ActionHolder>()

                    localScope.launch {
                        localBackFlow.collect {
                            val currentSession = session.value
                            val sendToMessengerFlag = it.action is Action.Messenger
                                    && currentSession?.projectSessions
                                ?.first { project -> project.id == it.projectId }
                                ?.observeMessenger == true
                            val sendToKanbanFlag = it.action is Action.Kanban
                                    && currentSession?.projectSessions
                                ?.first { project -> project.id == it.projectId }
                                ?.observeKanban == true
                            if (sendToKanbanFlag || sendToMessengerFlag || it.action is Action.Unauthorized) {
                                send(
                                    Frame.Text(
                                        Json.encodeToString(it.action).also {
                                            println("Sent: $it")
                                        }
                                    )
                                )
                            }
                        }
                    }

                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val receivedText = frame.readText()
                        println("Received: $receivedText")
                        try {

                            val projectId = getProjectId(receivedText)
                            val intent = Json.decodeFromString<Intent>(receivedText)

                            if (intent !is Intent.Authorize && session.value == null) {
                                localBackFlow.emit(
                                    ActionHolder(
                                        Action.Unauthorized, -1
                                    )
                                )
                            }

                            when (intent) {
                                is Intent.Authorize -> {
                                    session.emit(
                                        Context(
                                            user = integrationRepo.getUserFromJWT(intent.jwt),
                                            projectSessions = listOf()
                                        )
                                    )
                                }

                                Intent.CloseSession -> close()
                                is Intent.Kanban.Start -> {
                                    session.value?.projectSessions?.let {
                                        val newSessions = if (it.any { it.id == intent.projectId }) {
                                            it.map {
                                                if (it.id == intent.projectId) {
                                                    it.copy(
                                                        observeKanban = true
                                                    )
                                                } else {
                                                    it
                                                }
                                            }
                                        } else {
                                            it.toMutableList().apply {
                                                val backflow: ProjectBackFlowManager.ProjectBackFlow.BackFlow =
                                                    getBackFlow(intent.projectId, session)
                                                add(
                                                    ProjectSession(
                                                        id = intent.projectId,
                                                        projectBackFlow = backflow,
                                                        observeKanban = true,
                                                        observeMessenger = false
                                                    )
                                                )
                                            }
                                        }
                                        session.emit(
                                            session.value?.copy(projectSessions = newSessions)
                                        )
                                    }
                                }

                                is Intent.Kanban.Stop -> session.value?.let {
                                    session.emit(
                                        it.copy(
                                            projectSessions = it.projectSessions.map {
                                                if (it.id == intent.projectId) {
                                                    it.copy(
                                                        observeKanban = false
                                                    )
                                                } else {
                                                    it
                                                }
                                            }
                                        )
                                    )
                                }

                                is Intent.Messenger.Start -> {
                                    session.value?.projectSessions?.let {
                                        val newSessions = if (it.any { it.id == intent.projectId }) {
                                            it.map {
                                                if (it.id == intent.projectId) {
                                                    it.copy(
                                                        observeMessenger = true
                                                    )
                                                } else {
                                                    it
                                                }
                                            }
                                        } else {
                                            it.toMutableList().apply {
                                                val backflow: ProjectBackFlowManager.ProjectBackFlow.BackFlow =
                                                    getBackFlow(intent.projectId, session)
                                                add(
                                                    ProjectSession(
                                                        id = intent.projectId,
                                                        projectBackFlow = backflow,
                                                        observeKanban = false,
                                                        observeMessenger = true
                                                    )
                                                )
                                            }
                                        }
                                        session.emit(
                                            session.value?.copy(
                                                projectSessions = newSessions
                                            )
                                        )
                                    }
                                }

                                is Intent.Messenger.Stop -> session.value?.let {
                                    session.emit(
                                        it.copy(
                                            projectSessions = it.projectSessions.map {
                                                if (it.id == intent.projectId) {
                                                    it.copy(
                                                        observeMessenger = false
                                                    )
                                                } else {
                                                    it
                                                }
                                            }
                                        )
                                    )
                                }

                                else -> {
                                    val sessionValue = session.value
                                    handleIntent(
                                        localScope,
                                        intent,
                                        sessionValue?.user ?: continue,
                                        sessionValue?.projectSessions?.first {
                                            it.id == projectId
                                        } ?: continue,
                                        localBackFlow,
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            println(e.toString())
                        }
                    }
                    localScope.cancel()
                    session.value?.projectSessions?.let {
                        it.forEach {
                            ProjectBackFlowManager.unsubscribe(it.id)
                        }
                    }
                }
            }
        }
    }

    private fun getProjectId(text: String): Int {
        if (text.contains("entities.Intent.Authorize")) return -1
        val part = text.split("projectId")
        var num = ""
        var addFlag = true
        part[1].forEachIndexed { index, it ->
            if (index != 0 && index != 1 && addFlag) {
                if (it in "1234567890") {
                    num += it
                } else addFlag = false
            }
        }
        if (num == "") {
            throw Exception()
        }
        return num.toInt()
    }

    private fun DefaultWebSocketServerSession.getBackFlow(
        projectId: Int,
        session: MutableStateFlow<Context?>
    ): ProjectBackFlowManager.ProjectBackFlow.BackFlow {
        val backflow: ProjectBackFlowManager.ProjectBackFlow.BackFlow = ProjectBackFlowManager.getProjectBackFlow(
            projectId = projectId,
        ).apply {
            launch {
                while (session.value != null) {
                    // react-front drop socket-connection after some period of inaction
                    // this is required to keep it alive
                    send(
                        Frame.Text(
                            Json.encodeToString(Action.KeepAlive)
                        )
                    )
                    delay(10_000)
                }
            }
            collect {
                val currentSession = session.value
                val sendToMessengerFlag = it.action is Action.Messenger
                        && currentSession?.projectSessions
                    ?.first { project -> project.id == it.projectId }
                    ?.observeMessenger == true
                val sendToKanbanFlag = it.action is Action.Kanban
                        && currentSession?.projectSessions
                    ?.first { project -> project.id == it.projectId }
                    ?.observeKanban == true
                if (sendToKanbanFlag || sendToMessengerFlag) {
                    send(
                        Frame.Text(
                            Json.encodeToString(it.action).also {
                                println("Sent: $it")
                            }
                        )
                    )
                }
            }
        }
        return backflow
    }

    private fun extractTokens(input: String): Pair<String?, String?> {
        val params = input.split("&").associate {
            val (key, value) = it.split("=")
            key to value
        }

        val accessToken = params["access_token"]
        val refreshToken = params["refresh_token"]

        return Pair(accessToken, refreshToken)
    }

    private fun handleIntent(
        scope: CoroutineScope,
        intent: Intent,
        user: User,
        session: ProjectSession,
        localBackFlow: MutableSharedFlow<ActionHolder>
    ) {
        when (intent) {
            is Intent.Messenger -> {
                handleMessengerIntent(
                    scope, intent, user, session, localBackFlow
                )
            }

            is Intent.Kanban -> {
                handleKanbanIntent(
                    scope, intent, user, session, localBackFlow
                )
            }

            is Intent.Authorize -> { /* handled before */
            }

            Intent.CloseSession -> { /* handled before */
            }
        }
    }

    private fun handleKanbanIntent(
        scope: CoroutineScope,
        intent: Intent.Kanban,
        user: User,
        session: ProjectSession,
        localBackFlow: MutableSharedFlow<ActionHolder>
    ) {

        fun run(block: suspend () -> Unit) {
            scope.launch {
                block()
                session.projectBackFlow.emit(
                    Action.Kanban.SetState(
                        kanbanRepository.getKanban(session.id),
                        projectId = session.id
                    ),
                    projectId = session.id
                )
            }
        }

        when (intent) {

            is Intent.Kanban.CreateKard -> {
                run {
                    kanbanRepository.createKard(
                        name = intent.name,
                        columnId = intent.columnId,
                        desc = intent.desc,
                        authorId = user.id
                    )
                }
            }

            is Intent.Kanban.MoveKard -> {
                run {
                    kanbanRepository.moveKard(
                        columnId = intent.columnId,
                        kardId = intent.id,
                        newOrder = intent.newPosition,
                        newColumnId = intent.newColumnId
                    )
                }
            }

            is Intent.Kanban.GetKanban -> {
                scope.launch {
                    val kanban = kanbanRepository.getKanban(session.id)
                    session.projectBackFlow.emit(
                        SetState(kanban, session.id),
                        session.id
                    )
                }
            }

            is Intent.Kanban.Start -> { /* handled before */
            }

            is Intent.Kanban.Stop -> { /* handled before */
            }

            is Intent.Kanban.CreateColumn -> {
                run {
                    kanbanRepository.createColumn(
                        projectId = session.id,
                        name = intent.name
                    )
                }
            }

            is Intent.Kanban.MoveColumn -> {
                run {
                    kanbanRepository.moveColumn(
                        projectId = session.id,
                        columnId = intent.id,
                        newOrder = intent.newPosition
                    )
                }
            }

            is Intent.Kanban.UpdateKard -> {
                run {
                    kanbanRepository.updateKard(
                        id = intent.id,
                        desc = intent.desc,
                        name = intent.name,
                    )
                }
            }

            is Intent.Kanban.UpdateColumn -> {
                run {
                    kanbanRepository.updateColumn(intent.id, intent.name)
                }
            }

            is Intent.Kanban.DeleteColumn -> {
                run {
                    kanbanRepository.deleteColumn(intent.id)
                }
            }

            is Intent.Kanban.DeleteKard -> {
                run {
                    kanbanRepository.deleteKard(intent.id)
                }
            }
        }
    }

    private fun handleMessengerIntent(
        scope: CoroutineScope,
        intent: Intent.Messenger,
        user: User,
        session: ProjectSession,
        localBackFlow: MutableSharedFlow<ActionHolder>
    ) {
        when (intent) {
            is Intent.Messenger.SendMessage -> {
                scope.launch {
                    val message = messagesRepo.createMessage(
                        chatId = intent.chatId,
                        senderId = user.id,
                        text = intent.message
                    )

                    unreadMessagesRepo.readMessagesBefore(
                        messageId = message.id,
                        chatId = intent.chatId,
                        userId = user.id
                    )

                    session.projectBackFlow.emit(
                        NewMessage(
                            chatId = intent.chatId,
                            message = message,
                            projectId = session.id
                        ),
                        session.id
                    )
                }
            }

            is Intent.Messenger.CreateChat -> {
                scope.launch {
                    val chat = chatsRepo.createChat(
                        title = intent.chatTitle,
                        projectId = intent.projectId,
                        chatType = intent.chatType
                    )

                    session.projectBackFlow.emit(
                        NewChat(
                            chat = chat,
                            session.id
                        ),
                        projectId = session.id
                    )
                }
            }

            is Intent.Messenger.RequestChatMessages -> {
                scope.launch {
                    val unreadMessagesIds = messagesRepo.getUnreadMessagesId(
                        chatId = intent.chatId,
                        userId = user.id
                    )
                    val readMessages = mutableListOf<Message>()
                    val unreadMessages = mutableListOf<Message>()
                    messagesRepo.getChatMessages(
                        chatId = intent.chatId
                    ).forEach {
                        if (it.id in unreadMessagesIds) {
                            unreadMessages.add(it)
                        } else readMessages.add(it)
                    }
                    localBackFlow.emit(
                        ActionHolder(
                            projectId = session.id,
                            action = SendChatMessages(
                                chatId = intent.chatId,
                                readMessages = readMessages,
                                unreadMessages = unreadMessages,
                                projectId = session.id
                            )
                        )
                    )
                }
            }

            is Intent.Messenger.RequestChatsList -> {
                scope.launch {

                    val chats = chatsRepo.getProjectChats(
                        projectId = intent.projectId,
                        userType = user.type
                    ).map {

                        it.copy(
                            unreadMessagesCount = unreadMessagesRepo.getUnreadMessagesCount(
                                userId = user.id,
                                chatId = it.id
                            ),
                            lastMessage = messagesRepo.getLastMessage(chatId = it.id),
                        )
                    }

                    localBackFlow.emit(
                        ActionHolder(
                            projectId = session.id,
                            action = SendChatsList(
                                chats = chats,
                                projectId = session.id
                            )
                        )
                    )
                }
            }

            is Intent.Messenger.ReadMessage -> {
                scope.launch {
                    messagesRepo.readMessage(
                        messageId = intent.messageId,
                        userId = user.id
                    )
                    localBackFlow.emit(
                        ActionHolder(
                            projectId = session.id,
                            action = MessageReadRecorded(
                                messageId = intent.messageId,
                                chatId = intent.chatId,
                                projectId = session.id
                            )
                        )
                    )
                }
            }

            is Intent.Messenger.ReadMessagesBefore -> {
                scope.launch {
                    unreadMessagesRepo.readMessagesBefore(
                        messageId = intent.messageId,
                        chatId = intent.chatId,
                        userId = user.id
                    )
                    val unreadCount = unreadMessagesRepo.getUnreadMessagesCount(
                        userId = user.id,
                        chatId = intent.chatId
                    )
                    localBackFlow.emit(
                        ActionHolder(
                            projectId = session.id,
                            action = UpdateChatUnreadCount(
                                chatId = intent.chatId,
                                count = unreadCount,
                                projectId = session.id
                            )
                        )
                    )
                }
            }

            is Intent.Messenger.Start -> { /* handled before */
            }

            is Intent.Messenger.Stop -> { /* handled before */
            }
        }
    }

    private suspend fun getAccessToken(userId: String): String? {

        val accessToken = githubTokensRepo.getAccessToken(userId)
        if (accessToken is GithubTokensRepo.Token.Alive) {
            return accessToken.token
        }

        githubTokensRepo.getRefreshToken(userId)?.let { refresh ->
            if (refresh is GithubTokensRepo.Token.Expired) return null
            val response = httpClient.get("https://github.com/login/oauth/") {
                parameter("client_id", Config.Github.clientId)
                parameter("client_secret", Config.Github.clientSecret)
                parameter("grant_type", "refresh_token")
                parameter("refresh_token", (refresh as GithubTokensRepo.Token.Alive).token)
            }
            if (response.status == HttpStatusCode.OK) {
                val body = response.body<GithubTokens>()
                //val (accessToken, refreshToken) = extractTokens(body)

                if (body.access_token == null || body.refresh_token == null) {
                    return null
                }
                githubTokensRepo.updateTokens(
                    access = body.access_token,
                    refresh = body.refresh_token,
                    userId = userId,
                )
                return body.access_token
            } else return null
        }
        return null
    }
}