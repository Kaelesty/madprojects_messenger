package domain.profile

import domain.auth.User

interface ProfileRepo {

    suspend fun getCommonById(userId: String): CommonUser?

    suspend fun getCuratorById(userId: String): CuratorUser?

    suspend fun update(
        userId: String,
        firstName: String?,
        secondName: String?,
        lastName: String?,
        group: String?,
    )

    suspend fun checkIsCurator(userId: String): Boolean
}