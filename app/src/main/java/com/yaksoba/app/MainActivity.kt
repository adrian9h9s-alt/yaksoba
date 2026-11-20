package com.yaksoba.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { YaksobaScreen() } }
    }
}

// Executa o código Lua em segundo plano, com limite de tempo, e devolve o que o script imprimiu.
suspend fun runLua(code: String, timeoutMs: Long = 5000): String = withContext(Dispatchers.Default) {
    val buffer = ByteArrayOutputStream()
    val globals = JsePlatform.debugGlobals()
    globals.STDOUT = PrintStream(buffer, true)

    // Guarda a função sethook antes de esconder a biblioteca debug do usuário.
    val sethook = globals.get("debug").get("sethook")

    // Sandbox básico: remove acesso a arquivos, sistema, Java e debug.
    listOf("io", "os", "luajava", "debug").forEach { globals.set(it, LuaValue.NIL) }

    try {
        val chunk = globals.load(code, "script")
        val thread = LuaThread(globals, chunk)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000

        // A cada 1000 instruções Lua, confere se o tempo acabou.
        val hook = object : ZeroArgFunction() {
            override fun call(): LuaValue {
                if (System.nanoTime() > deadline) {
                    throw Error("Tempo limite excedido (${timeoutMs / 1000}s)")
                }
                return LuaValue.NIL
            }
        }
        sethook.invoke(
            LuaValue.varargsOf(
                arrayOf<LuaValue>(thread, hook, LuaValue.EMPTYSTRING, LuaValue.valueOf(1000))
            )
        )

        val result = thread.resume(LuaValue.NONE)
        if (!result.arg1().toboolean()) {
            buffer.write("Erro: ${result.arg(2).tojstring()}\n".toByteArray())
        }
    } catch (e: Exception) {
        buffer.write("Erro: ${e.message}\n".toByteArray())
    }
    buffer.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YaksobaScreen() {
    var code by remember { mutableStateOf("print(\"Olá, Yaksoba!\")\nfor i = 1, 3 do\n  print(i * 2)\nend") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Abrir: seletor de arquivos do sistema (Storage Access Framework).
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()?.use { r -> r.readText() }
                if (text != null) code = text
            } catch (e: Exception) {
                output = "Erro ao abrir: ${e.message}"
            }
        }
    }

    // Salvar: o usuário escolhe nome e local do arquivo.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)
                    ?.bufferedWriter()?.use { w -> w.write(code) }
                output = "Script salvo."
            } catch (e: Exception) {
                output = "Erro ao salvar: ${e.message}"
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Yaksoba") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("Abrir") }
                OutlinedButton(
                    onClick = { saveLauncher.launch("script.lua") },
                    modifier = Modifier.weight(1f)
                ) { Text("Salvar") }
            }

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Script Lua") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        output = runLua(code)
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (running) "Executando..." else "Executar") }

            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Text(
                    output,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
