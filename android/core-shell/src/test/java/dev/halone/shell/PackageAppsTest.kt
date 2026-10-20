package dev.halone.shell

import dev.halone.compositor.Scene
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageAppsTest {
    private val temp: File = Files.createTempDirectory("halone-packages").toFile()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun manifest(
        version: String = "1.0.0",
        permissions: List<String> = listOf("network.tcp"),
        id: String = "dev.example.ssh",
    ) = JSONObject()
        .put("id", id)
        .put("name", "SSH")
        .put("version", version)
        .put("icon", "icon.png")
        .put("window", JSONObject().put("width", 1000).put("height", 700))
        .put("permissions", JSONArray(permissions))

    private fun pack(name: String, files: Map<String, ByteArray>): File {
        val file = File(temp, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun pkg(manifest: JSONObject = manifest(), extra: Map<String, ByteArray> = emptyMap(), name: String = "app.halone") = pack(
        name,
        mapOf("halone.json" to manifest.toString().toByteArray(), "index.html" to "<h1>hi</h1>".toByteArray(), "icon.png" to png) + extra,
    )

    private fun error(block: () -> Unit) = (runCatching(block).exceptionOrNull() as HaloneException).message.orEmpty()

    @Test
    fun `a manifest needs a reverse-DNS id, a version, a name and only known permissions`() {
        val parsed = PackageManifest.parse(manifest().toString())
        assertEquals(listOf(Permission.NETWORK_TCP), parsed.permissions)
        assertEquals("index.html", parsed.entry)
        assertEquals(1000, parsed.widthPx)

        assertTrue(error { PackageManifest.parse(manifest(id = "SSH").toString()) }.contains("reverse-DNS"))
        assertTrue(error { PackageManifest.parse(manifest(version = "1.0").toString()) }.contains("major.minor.patch"))
        assertTrue(error { PackageManifest.parse(manifest(permissions = listOf("root")).toString()) }.contains("unknown permission: root"))
        assertTrue(error { PackageManifest.parse(manifest(id = "dev.files").toString(), reserved = setOf("dev.files")) }.contains("reserved"))
        assertTrue(error { PackageManifest.parse(manifest().put("entry", "../index.html").toString()) }.contains("not a path"))
    }

    @Test
    fun `a manifest may name its tile colour as #rrggbb, kept through a reinstall`() {
        val parsed = PackageManifest.parse(manifest().put("color", "#1A2B3C").toString())
        assertEquals("#1a2b3c", parsed.color)
        assertEquals("#1a2b3c", PackageManifest.parse(parsed.toJson().toString()).color)
        assertNull(PackageManifest.parse(manifest().toString()).color)
        assertTrue(error { PackageManifest.parse(manifest().put("color", "red").toString()) }.contains("#rrggbb"))
    }

    @Test
    fun `a manifest needing a newer Halone than the running one is refused, an older or equal one is not`() {
        val needs = manifest().put("minHalone", "2026.10.12")
        assertEquals("2026.10.12", PackageManifest.parse(needs.toString(), halone = "2026.10.12-alpha").minHalone)
        assertEquals("2026.10.12", PackageManifest.parse(PackageManifest.parse(needs.toString()).toJson().toString()).minHalone)
        PackageManifest.parse(needs.toString(), halone = "2026.11.01-alpha")
        val refused = runCatching { PackageManifest.parse(needs.toString(), halone = "2026.10.11-alpha") }.exceptionOrNull() as HaloneException
        assertEquals("incompatible", refused.code)
        assertTrue(refused.message!!.contains("2026.10.12 or newer; this is 2026.10.11"))
        assertTrue(error { PackageManifest.parse(manifest().put("minHalone", "next").toString()) }.contains("minHalone must be"))
        assertEquals("incompatible", (runCatching { HalonePackage.read(pkg(needs), halone = "2026.10.11") }.exceptionOrNull() as HaloneException).code)
    }

    @Test
    fun `a package must hold its manifest, its entry and a PNG icon, and nothing outside itself`() {
        assertEquals("dev.example.ssh", HalonePackage.read(pkg()).manifest.id)
        assertTrue(error { HalonePackage.read(pack("a.halone", mapOf("index.html" to ByteArray(1)))) }.contains("halone.json is missing"))
        assertTrue(error { HalonePackage.read(pkg(manifest().put("entry", "app.html"), name = "b.halone")) }.contains("entry app.html"))
        assertTrue(error { HalonePackage.read(pkg(extra = mapOf("icon.png" to ByteArray(8)), name = "c.halone")) }.contains("not a PNG"))
        assertTrue(error { HalonePackage.read(pkg(extra = mapOf("../evil.sh" to ByteArray(1)), name = "d.halone")) }.contains("not a path"))
        assertTrue(error { HalonePackage.read(File(temp, "missing.halone").apply { writeText("not a zip") }) }.contains("not a .halone"))
    }

    private val root = File(temp, "apps")
    private val store = MemoryStore()

    @Test
    fun `installing unpacks into a folder per version, and an update replaces it and needs a higher version`() {
        val apps = PackageApps(root, store, clock = { 5L })
        val first = apps.install(HalonePackage.read(pkg()), "example.com")
        assertEquals("https://dev.example.ssh.apps.halone.local/index.html", first.entryUrl)
        assertTrue(File(root, "dev.example.ssh/1.0.0/index.html").isFile)

        val update = HalonePackage.read(pkg(manifest(version = "1.1.0", permissions = listOf("network.tcp", "clipboard")), name = "u.halone"))
        assertEquals(listOf(Permission.CLIPBOARD), apps.newPermissions(update))
        apps.install(update, "example.com")
        assertFalse(File(root, "dev.example.ssh/1.0.0").exists())
        assertTrue(File(root, "dev.example.ssh/1.1.0/index.html").isFile)
        assertEquals(setOf(Permission.NETWORK_TCP, Permission.CLIPBOARD), apps.find("dev.example.ssh")!!.granted)

        assertEquals("not-newer", (runCatching { apps.install(HalonePackage.read(pkg()), "x") }.exceptionOrNull() as HaloneException).code)
        assertEquals(apps.list(), PackageApps(root, store).list())

        apps.remove("dev.example.ssh")
        assertFalse(File(root, "dev.example.ssh").exists())
        assertTrue(apps.list().isEmpty())
    }

    @Test
    fun `a dev app loads from its server, has no files, and goes with developer mode`() {
        val apps = PackageApps(root, store)
        val app = apps.installDev(PackageManifest.parse(manifest().toString()), "http://localhost:5180/index.html")
        assertEquals("http://localhost:5180/index.html", app.entryUrl)
        assertEquals("http://localhost:5180/icon.png", apps.iconUrl(app))
        assertNull(apps.filesOf(app.id))
        assertTrue(apps.list().single().toAppInfo(null).dev)
        apps.removeDevApps()
        assertTrue(apps.list().isEmpty())
    }

    private val scene = Scene()
    private val platform = FakePlatform(scene)
    private val packages = PackageApps(root, store)
    private val shell = Shell(scene, APPS, ShellSettings(MemoryStore(), SCHEMA), platform, packageApps = packages)
    private val host = BridgeHost(shell, SystemInfo("test", "Test Glasses", 19.0, 1920, 1080))

    private fun installed(permissions: List<String> = listOf("network.tcp")) =
        shell.installPackage(HalonePackage.read(pkg(manifest(permissions = permissions))), "example.com")

    @Test
    fun `an installed app is listed and opens its own origin with its files`() {
        val app = installed()
        val info = shell.apps.last()
        assertEquals(AppKind.PACKAGE, info.kind)
        assertEquals("https://dev.example.ssh.apps.halone.local/icon.png", info.iconUrl)
        assertEquals(listOf("network.tcp"), info.permissions)

        val window = shell.launch(app.id)
        assertEquals(window.id to app.entryUrl, platform.opened.last())
        assertEquals(File(root, "dev.example.ssh/1.0.0"), platform.packageFiles[window.id])
        assertEquals(setOf(Permission.NETWORK_TCP), shell.grantOf(window.id))
        assertNull(shell.grantOf(shell.launch("files").id))
    }

    private fun FakeChannel.call(method: String, params: JSONObject = JSONObject()): JSONObject {
        receive(JSONObject().put("type", "req").put("id", 1).put("method", method).put("params", params).toString())
        return JSONObject(posted.last { JSONObject(it).optString("type") == "res" })
    }

    private fun FakeChannel.code(method: String, params: JSONObject = JSONObject()) =
        call(method, params).optJSONObject("error")?.optString("code") ?: "ok"

    @Test
    fun `the bridge lets an app run its own window, and only what its grant covers besides`() {
        val window = shell.launch(installed(listOf("clipboard")).id)
        val other = shell.launch("files")
        val app = FakeChannel().also { host.connect(window.id, it) }

        assertEquals("ok", app.code("windows.self"))
        assertEquals("ok", app.code("windows.setTitle", JSONObject().put("title", "Session")))
        assertEquals("ok", app.code("clipboard.read"))
        assertEquals("ok", app.code("display.state"))
        assertEquals("permission-denied", app.code("windows.list"))
        assertTrue(app.call("windows.list").getJSONObject("error").getString("message").contains("windows permission"))
        assertEquals("permission-denied", app.code("settings.get"))
        assertEquals("permission-denied", app.code("bookmarks.list"))
        assertEquals("permission-denied", app.code("windows.close", JSONObject().put("id", other.id)))
        assertEquals("permission-denied", app.code("plugins.list"))
        assertEquals("permission-denied", app.code("status.list"))
        assertEquals("permission-denied", app.code("system.shareLog"))
        assertEquals("permission-denied", app.code("display.restart"))
        assertEquals("permission-denied", app.code("plugins.setEnabled", JSONObject().put("packageName", "dev.example.plugin").put("enabled", true)))
        assertTrue(shell.windows().any { it.id == other.id })
    }

    @Test
    fun `an app hears about its own window but not others' unless granted`() {
        val window = shell.launch(installed().id)
        val app = FakeChannel().also { host.connect(window.id, it) }
        val system = FakeChannel().also { host.connect(shell.launch("files").id, it) }
        shell.setTitle(window.id, "renamed")

        fun FakeChannel.heard(name: String) = posted.any { JSONObject(it).optString("name") == name }
        assertTrue(system.heard("windows.changed"))
        assertFalse(app.heard("windows.changed"))
        shell.setSetting(ShellSettings.FOLLOW_ENABLED, false)
        assertFalse(app.heard("settings.changed"))
    }

    @Test
    fun `the install prompt lists what the app may do, and installs only when accepted`() {
        val browser = shell.openUrl("https://example.com/")
        var installed: InstalledApp? = null
        shell.offerPackage(browser.id, HalonePackage.read(pkg(manifest(permissions = listOf("network.tcp", "clipboard")))), "example.com") { installed = it }
        val dialog = shell.menu!!.dialog!!
        assertEquals("Install “SSH”?", dialog.getString("title"))
        assertEquals(
            "1.0.0 from example.com\n\nIt will be able to:\n•  Connect to any device or server\n•  Read what you copy",
            dialog.getString("message"),
        )
        shell.chooseMenu(shell.menu!!.menuId, "install")
        assertEquals("dev.example.ssh", installed?.id)

        shell.offerPackage(browser.id, HalonePackage.read(pkg(manifest(version = "1.1.0", permissions = listOf("network.tcp", "clipboard", "files")), name = "u.halone")), "example.com") { installed = it }
        assertEquals("Update “SSH”?", shell.menu!!.dialog!!.getString("title"))
        assertEquals("1.0.0 → 1.1.0 from example.com\n\nIt will also be able to:\n•  Read and change your files", shell.menu!!.dialog!!.getString("message"))
        shell.chooseMenu(shell.menu!!.menuId, "cancel")
        assertNull(installed)
        assertEquals("1.0.0", packages.find("dev.example.ssh")!!.manifest.version)

        shell.offerPackage(browser.id, HalonePackage.read(pkg(name = "same.halone")), "example.com") { installed = it }
        assertNull(shell.menu)
        assertEquals("SSH 1.0.0 is already installed", platform.notices.last())
    }

    @Test
    fun `installing over a running app closes it, and uninstalling removes its files`() {
        val window = shell.launch(installed().id)
        shell.installPackage(HalonePackage.read(pkg(manifest(version = "2.0.0"), name = "v2.halone")), "example.com")
        assertTrue(shell.windows().none { it.id == window.id })
        assertEquals(listOf("Installed “SSH”", "Installed “SSH”"), platform.notices)

        shell.uninstall("dev.example.ssh")
        assertFalse(File(root, "dev.example.ssh").exists())
    }
}

class PermissionTableTest {
    private val table = JSONObject(File("../../sdk/packages/sdk/src/permissions.json").readText())

    private fun strings(key: String) = table.getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }

    private fun map(key: String) = table.getJSONObject(key).let { json -> json.keys().asSequence().associateWith { json.getString(it) } }

    @Test
    fun `the bridge enforces the table the SDK and the simulator share`() {
        assertEquals(strings("openToAll"), BridgeHost.OPEN_TO_ALL)
        assertEquals(strings("selfOnly"), BridgeHost.SELF_ONLY)
        assertEquals(map("methods"), BridgeHost.PERMISSIONS.mapValues { it.value.wire })
        assertEquals(strings("ownEvents"), BridgeHost.OWN_EVENTS)
        assertEquals(strings("permissions"), Permission.entries.map { it.wire }.toSet())
        val events = map("events")
        listOf("windows.changed", "apps.changed", "files.accessChanged", "screen.recordingChanged").forEach { assertTrue(it in events) }
        assertEquals(4, events.size)
    }
}
