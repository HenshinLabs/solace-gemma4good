package com.masterllm.testing.shared

import com.masterllm.core.domain.repository.TokenRepository

class FakeTokenRepository : TokenRepository {
    private var token: String? = null

    override suspend fun getToken(): String? = token

    override suspend fun setToken(token: String) {
        this.token = token
    }

    override suspend fun clearToken() {
        token = null
    }
}
