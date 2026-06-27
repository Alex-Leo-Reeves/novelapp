package com.alexleoreeves.novelapp.platform

data class SavedUserAccount(
    val username: String,
    val email: String,
    val authToken: String
)

interface UserSessionStore {
    fun loadAccount(): SavedUserAccount?
    fun saveAccount(account: SavedUserAccount)
    fun clearAccount()
}

object EmptyUserSessionStore : UserSessionStore {
    override fun loadAccount(): SavedUserAccount? = null
    override fun saveAccount(account: SavedUserAccount) = Unit
    override fun clearAccount() = Unit
}
