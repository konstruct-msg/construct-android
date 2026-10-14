package com.construct.messenger.ui

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import com.construct.messenger.data.repository.ServerRefusedException
import com.construct.messenger.invite.InviteException
import com.construct.messenger.invite.InviteConfig
import com.construct.messenger.invite.InviteProblem
import io.grpc.Status
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a person sees comes from a closed set, chosen by type and code — never by an exception's
 * text (vault TODO 128). Every message below carries a marker that must not decide anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UserErrorTest {

    private val marker = "LEAK-1234"

    private fun grpc(code: Status.Code) = Status.fromCode(code).withDescription(marker).asRuntimeException()

    @Test
    fun grpcCodesMapToTheirCase() {
        assertEquals(UserError.NO_CONNECTION, UserError.from(grpc(Status.Code.UNAVAILABLE)))
        assertEquals(UserError.NO_CONNECTION, UserError.from(grpc(Status.Code.DEADLINE_EXCEEDED)))
        assertEquals(UserError.NO_CONNECTION, UserError.from(grpc(Status.Code.CANCELLED)))
        assertEquals(UserError.SIGN_IN_AGAIN, UserError.from(grpc(Status.Code.UNAUTHENTICATED)))
        assertEquals(UserError.TOO_MANY_ATTEMPTS, UserError.from(grpc(Status.Code.RESOURCE_EXHAUSTED)))
        assertEquals(UserError.SERVER_FAILED, UserError.from(grpc(Status.Code.INTERNAL)))
        assertEquals(UserError.SERVER_FAILED, UserError.from(grpc(Status.Code.PERMISSION_DENIED)))
    }

    @Test
    fun networkExceptionsAreNoConnection() {
        listOf(
            UnknownHostException(marker),
            ConnectException(marker),
            SocketTimeoutException(marker),
            InterruptedIOException(marker),
            IOException(marker),
        ).forEach { assertEquals(it.javaClass.name, UserError.NO_CONNECTION, UserError.from(it)) }
    }

    @Test
    fun aFullDiskIsNotAConnectionProblem() {
        val full = IOException(marker, ErrnoException("write", OsConstants.ENOSPC))
        assertEquals(UserError.NO_SPACE, UserError.from(full))
        // Another errno under an IOException is just I/O.
        assertEquals(
            UserError.NO_CONNECTION,
            UserError.from(IOException(marker, ErrnoException("write", OsConstants.EIO))),
        )
    }

    @Test
    fun theCauseChainIsSearched() {
        assertEquals(UserError.NO_CONNECTION, UserError.from(RuntimeException(marker, grpc(Status.Code.UNAVAILABLE))))
        assertEquals(
            UserError.SIGN_IN_AGAIN,
            UserError.from(IllegalStateException(marker, RuntimeException(marker, grpc(Status.Code.UNAUTHENTICATED)))),
        )
    }

    @Test
    fun aServerRefusalIsNotAConnectionProblem() {
        assertEquals(UserError.SERVER_FAILED, UserError.from(ServerRefusedException(marker)))
    }

    @Test
    fun everythingElseIsGeneric() {
        assertEquals(UserError.SOMETHING_WENT_WRONG, UserError.from(RuntimeException("whatever")))
        // The words "unavailable" and "network" in a message decide nothing.
        assertEquals(UserError.SOMETHING_WENT_WRONG, UserError.from(IllegalStateException("UNAVAILABLE network unreachable")))
    }

    @Test
    fun inviteExceptionsCarryTheirKind() {
        assertEquals(InviteProblem.EXPIRED, InviteException.Expired.problem)
        assertEquals(InviteProblem.ALREADY_USED, InviteException.AlreadyUsed.problem)
        assertEquals(InviteProblem.DAMAGED, InviteException.BadMagic.problem)
        assertEquals(InviteProblem.DAMAGED, InviteException.InvalidEncoding.problem)
        assertEquals(InviteProblem.DAMAGED, InviteException.TtlBelowFloor(1).problem)
        assertEquals(InviteProblem.NO_RECOVERY_PHRASE, InviteException.NoAccountAddress.problem)
        assertEquals(InviteProblem.UNREACHABLE, InviteException.PublicKeyFetchFailed.problem)
        // A newer app's invite asks for an update; an older one is just unreadable.
        assertEquals(InviteProblem.NEWER_VERSION, InviteException.UnsupportedVersion(InviteConfig.VERSION + 1).problem)
        assertEquals(InviteProblem.DAMAGED, InviteException.UnsupportedVersion(InviteConfig.VERSION - 1).problem)
    }

    /** Every text the closed sets can show exists, and is translated. */
    @Test
    fun everyCaseHasAnEnglishAndARussianText() {
        val en = keys("src/main/res/values/strings.xml")
        val ru = keys("src/main/res/values-ru/strings.xml")
        val names = (UserError.entries.map { it.text } + InviteProblem.entries.map { it.text })
            .map { resourceName(it) }
        assertTrue(names.isNotEmpty())
        names.forEach {
            assertTrue("$it missing in values/strings.xml", it in en)
            assertTrue("$it missing in values-ru/strings.xml", it in ru)
        }
        // The loose ones the screens name directly.
        listOf(
            "synaps_user_not_found", "synaps_request_failed", "synaps_accept_failed",
            "synaps_status_added", "synaps_status_requested", "veil_start_failed",
            "username_unavailable", "username_reserved", "username_check_failed",
        ).forEach {
            assertTrue("$it missing in values/strings.xml", it in en)
            assertTrue("$it missing in values-ru/strings.xml", it in ru)
        }
    }

    private fun keys(path: String): Set<String> =
        Regex("""<string name="([^"]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    private fun resourceName(id: Int): String =
        org.robolectric.RuntimeEnvironment.getApplication().resources.getResourceEntryName(id)
}
