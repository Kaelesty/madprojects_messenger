package domain

import shared_domain.entities.UserMeta

interface GithubTokensRepo {

    suspend fun save(
        refresh: String,
        access: String,
        userId: String,
        githubId: Int,
        avatar: String,
        profileLink: String,
    )

    suspend fun updateTokens(userId: String, access: String, refresh: String)

    suspend fun getAccessToken(userId: String): Token?

    suspend fun getRefreshToken(userId: String): Token?

    suspend fun getUserMeta(githubUserId: Int): UserMeta?

    sealed interface Token {
        class Alive(val token: String): Token
        object Expired: Token
    }
}