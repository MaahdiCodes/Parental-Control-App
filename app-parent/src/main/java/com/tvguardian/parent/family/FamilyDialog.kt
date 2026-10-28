package com.tvguardian.parent.family

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

/** Who's in the family, invite another parent, join another parent's family, or leave. */
@Composable
fun FamilyDialog(user: FirebaseUser, familyId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val family by remember(familyId) { FamilyRepository.family(familyId) }.collectAsState(initial = null)
    val isOwner = familyId == user.uid
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var joinCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            runCatching { action() }.onFailure { message = it.message }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Family") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Parents in this family can all see and control the same TVs.")
                Text("Parents", style = MaterialTheme.typography.titleSmall)
                val owner = family?.emails?.get(familyId) ?: if (isOwner) user.email else null
                Text("• ${owner ?: "Owner"} (owner)" + if (isOwner) " · you" else "")
                family?.joinedParents?.forEach { uid ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "• ${family?.emails?.get(uid) ?: "Parent"}" + if (uid == user.uid) " · you" else "",
                            Modifier.weight(1f),
                        )
                        if (isOwner) {
                            TextButton(enabled = !busy, onClick = { run { FamilyRepository.removeParent(familyId, uid) } }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("Invite a parent", style = MaterialTheme.typography.titleSmall)
                val code = inviteCode
                if (code == null) {
                    Button(enabled = !busy, onClick = { run { inviteCode = FamilyRepository.createInvite(familyId, user) } }) {
                        Text("Create invite code")
                    }
                } else {
                    Text(code.chunked(4).joinToString(" "), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Valid for 24 hours. They install TV Guardian, sign in with their own Google account, " +
                            "then tap Family → Join and enter this code.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        val text = "Join my TV Guardian family: open the app, tap Family → Join and enter $code (valid 24 hours)."
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invite")
                        )
                    }) { Text("Share code…") }
                }

                HorizontalDivider()
                if (isOwner) {
                    Text("Join another parent's family", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "You'll see their TVs instead of yours. TVs you added yourself stay in your own family.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = joinCode, onValueChange = { joinCode = it }, label = { Text("Invite code") }, singleLine = true)
                    Button(enabled = !busy && joinCode.count(Char::isLetterOrDigit) == 8, onClick = {
                        run {
                            FamilyRepository.join(joinCode, user)
                            onDismiss()
                        }
                    }) { Text("Join") }
                } else {
                    OutlinedButton(enabled = !busy, onClick = {
                        run {
                            FamilyRepository.leave(familyId, user)
                            onDismiss()
                        }
                    }) { Text("Leave this family", color = MaterialTheme.colorScheme.error) }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
