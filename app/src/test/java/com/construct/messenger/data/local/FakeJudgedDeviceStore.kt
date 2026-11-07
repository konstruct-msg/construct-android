package com.construct.messenger.data.local

/** [JudgedDeviceStore] in memory, for tests; [JudgedDeviceStoreContract] holds it to the real one's behaviour. */
internal class FakeJudgedDeviceStore : JudgedDeviceStore {
    val judged = linkedSetOf<String>()
    val former = hashMapOf<String, Set<String>>()
    var seeded = false

    /** Every read and write throws while set. */
    var failing = false

    private fun check() {
        if (failing) throw java.io.IOException("store unavailable")
    }

    override suspend fun all(): Set<String> = judged.toSet().also { check() }

    override suspend fun add(deviceIds: Collection<String>) {
        check()
        judged += deviceIds
    }

    override suspend fun retire(accountId: String, deviceIds: Collection<String>) {
        check()
        val left = deviceIds.filter(judged::contains)
        if (left.isNotEmpty()) former[accountId] = left.toSet()
        judged -= deviceIds.toSet()
    }

    override suspend fun formerlyTrusted(accountId: String): Set<String> = former[accountId].orEmpty().also { check() }

    override suspend fun isSeeded(): Boolean = seeded.also { check() }

    override suspend fun seed(deviceIds: Collection<String>) {
        check()
        judged += deviceIds
        seeded = true
    }
}
