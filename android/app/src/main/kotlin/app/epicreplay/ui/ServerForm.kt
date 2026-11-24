package app.epicreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.epicreplay.core.models.CustomServer
import app.epicreplay.core.models.CustomServers

/**
 * The form of a cloud server of the user's own (spec 004 FR-CLOUD-1): any OpenAI-compatible server over HTTPS, in one place: name, address, model name, API key, the prices that the
 * spending caps count with, and whether it takes tool calls. [onSave] gets the server and the key field's text and returns the reason it was refused, or null. With a key already saved
 * ([keySaved]) the key field may stay empty and the saved key is kept.
 */
@Composable
fun CustomServerForm(initial: CustomServer?, keySaved: Boolean, onSave: (CustomServer, String) -> String?) {
    SecureWhileTyping()
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var base by remember { mutableStateOf(initial?.apiBase ?: "https://") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(initial?.priceInPerM?.takeIf { it > 0 }?.toString() ?: "") }
    var pout by remember { mutableStateOf(initial?.priceOutPerM?.takeIf { it > 0 }?.toString() ?: "") }
    var tools by remember { mutableStateOf(initial?.supportsTools ?: true) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(start = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server_label"))
        OutlinedTextField(base, { base = it; problem = null }, label = { Text("Base address, e.g. https://api.openai.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server_base"))
        OutlinedTextField(model, { model = it; problem = null }, label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server_model"))
        OutlinedTextField(
            apiKey, { apiKey = it; problem = null }, label = { Text(if (keySaved) "API key (empty keeps the saved one)" else "API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("server_key"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(pin, { pin = it; problem = null }, label = { Text("USD per million in") }, singleLine = true, modifier = Modifier.weight(1f).testTag("server_price_in"))
            OutlinedTextField(pout, { pout = it; problem = null }, label = { Text("USD per million out") }, singleLine = true, modifier = Modifier.weight(1f).testTag("server_price_out"))
        }
        Text("The prices let the spending caps count this server's cost; leave them empty if you do not know them (the caps then cannot count it).", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.toggleable(tools, role = SemanticsRole.Checkbox, onValueChange = { tools = it }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(tools, onCheckedChange = null); Text("It takes tool calls", Modifier.padding(start = 8.dp))
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("server_problem")) }
        Button(onClick = {
            problem = CustomServers.problem(label, base, model, pin, pout) ?: onSave(CustomServers.of(label, base, model, pin, pout, tools)!!, apiKey)
            if (problem == null) apiKey = ""
        }, modifier = Modifier.testTag("server_save")) { Text("Save") }
    }
}
