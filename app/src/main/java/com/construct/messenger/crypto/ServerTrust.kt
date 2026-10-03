package com.construct.messenger.crypto

/**
 * What the core judges a server signature by: the Ed25519 bundle key, and the delegations of the
 * server's hybrid keys. Handed over whole, before every event that can check a certificate.
 */
class ServerTrust(val keys: List<ByteArray>, val delegations: List<ByteArray>)
