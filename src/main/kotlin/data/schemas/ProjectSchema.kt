package data.schemas

import data.getCurrentDate
import domain.project.ProjectMeta
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

class ProjectService(
    database: Database
) {

    object Projects: Table("Projects_") {
        val id = integer("id").autoIncrement()
        val title = varchar("title", length = 25)
        val desc = varchar("desc", length = 1000)
        val maxMembersCount = integer("max_members_count")
        val creatorId = integer("creator_id")
            .references(UserService.Users.id)
        val createDate = varchar("create_date", length = 16)
            .nullable()
        val isDeleted = bool("is_deleted")
            .nullable()

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(Projects)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T) =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun create(title_: String, desc_: String, maxMembersCount_: Int, userId: Int) = dbQuery {
        val newId = Projects.insert {
            it[desc] = desc_
            it[title] = title_
            it[maxMembersCount] = maxMembersCount_
            it[createDate] = getCurrentDate()
            it[creatorId] = userId
            it[isDeleted] = false
        }[Projects.id]

        return@dbQuery newId.toString()
    }

    suspend fun getById(projectId_: Int) = dbQuery {
        Projects.selectAll()
            .where { Projects.id eq projectId_}
            .map {
                ProjectMeta(
                    id = it[Projects.id].toString(),
                    title = it[Projects.title],
                    desc = it[Projects.desc],
                    maxMembersCount = it[Projects.maxMembersCount],
                    createDate = it[Projects.createDate] ?: "00.00.0000"
                )
            }
            .first()
    }

    suspend fun isProjectDeleted(projectId_: Int) = dbQuery {
        Projects.selectAll()
            .where { Projects.id eq projectId_ }
            .map { it[Projects.isDeleted] }
            .first() == true
    }

    suspend fun getCreatorId(projectId_: Int) = dbQuery {
        Projects.selectAll()
            .where { Projects.id eq projectId_}
            .map {
                it[Projects.creatorId]
            }
            .first()
    }

    suspend fun deleteProject(projectId_: Int) = dbQuery {
        Projects.update(
            where = { Projects.id eq projectId_ }
        ) {
            it[isDeleted] = true
        }
    }

    suspend fun update(projectId_: Int, title_: String?, desc_: String?) = dbQuery {
        Projects.update(
            where = { Projects.id eq projectId_ }
        ) {
            title_?.let { title_ ->
                it[title] = title_
            }
            desc_?.let { desc_ ->
                it[desc] = desc_
            }
        }
    }
}