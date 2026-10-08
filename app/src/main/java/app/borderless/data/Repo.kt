package app.borderless.data

import android.content.Context
import android.util.Log
import app.borderless.config.LinkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * How every data file is read and written. Old files must always stay readable by newer versions:
 * unknown keys (removed fields) are ignored, unknown enum values fall back to defaults, and new fields
 * need defaults. Tested against files written by every release (`CompatTest`, `test/resources/compat`).
 */
internal val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

/** Persistent app state. All mutations go through here; files are written asynchronously. */
object Repo {
    private const val TAG = "Repo"
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val json = StoreJson

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    private val _pings = MutableStateFlow<Map<String, PingRecord>>(emptyMap())
    private val _settings = MutableStateFlow(AppSettings())
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _auto = MutableStateFlow(AutoState())

    val servers: StateFlow<List<Server>> = _servers.asStateFlow()
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()
    val pings: StateFlow<Map<String, PingRecord>> = _pings.asStateFlow()
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Groups in display order. */
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    val auto: StateFlow<AutoState> = _auto.asStateFlow()

    /** Servers that take part in pinging and selection, in list order; updates with visibility changes. */
    val visible: StateFlow<List<Server>> = combine(_servers, _settings, _auto) { list, s, a -> list.filter { isActive(it, s, a) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val serversSer = ListSerializer(Server.serializer())
    private val subsSer = ListSerializer(Subscription.serializer())
    private val pingsSer = MapSerializer(String.serializer(), PingRecord.serializer())
    private val groupsSer = ListSerializer(Group.serializer())

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        dir = File(context.filesDir, "data").apply { mkdirs() }
        _servers.value = read("servers.json", serversSer) ?: emptyList()
        _subscriptions.value = read("subscriptions.json", subsSer) ?: emptyList()
        _pings.value = read("pings.json", pingsSer) ?: emptyMap()
        _settings.value = read("settings.json", AppSettings.serializer()) ?: AppSettings.fresh()
        _groups.value = read("groups.json", groupsSer) ?: emptyList()
        _auto.value = read("auto.json", AutoState.serializer()) ?: AutoState()
        checkGroups()
        initialized = true
    }

    /**
     * Makes sure the default group exists, every subscription points to an existing group and every
     * server has a group (a damaged or hand-edited file must not leave anything homeless).
     */
    private fun checkGroups() {
        val before = _groups.value
        val groups = before.toMutableList()
        if (groups.none { it.id == Group.DEFAULT_ID }) groups.add(0, Group(Group.DEFAULT_ID))
        val subsBefore = _subscriptions.value
        val subs = subsBefore.map { sub ->
            if (sub.groupId != null && groups.any { it.id == sub.groupId }) sub else sub.copy(groupId = Group.DEFAULT_ID)
        }
        if (groups != before) {
            _groups.value = groups
            saveGroups()
        }
        if (subs != subsBefore) {
            _subscriptions.value = subs
            write("subscriptions.json", subsSer) { _subscriptions.value }
        }
        val ids = groups.map { it.id }.toSet()
        val subGroup = subs.associate { it.id to it.groupId }
        val fixed = _servers.value.map { srv ->
            val wanted = when {
                srv.subscriptionId != null -> subGroup[srv.subscriptionId] ?: Group.DEFAULT_ID
                srv.groupId in ids -> srv.groupId
                else -> Group.DEFAULT_ID
            }
            if (srv.groupId != wanted) srv.copy(groupId = wanted) else srv
        }
        if (fixed != _servers.value) {
            _servers.value = fixed
            saveServers()
        }
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        _settings.update(f)
        write("settings.json", AppSettings.serializer()) { _settings.value }
    }

    // ------------------------------------------------------------------ servers

    fun server(id: String?): Server? = id?.let { i -> _servers.value.firstOrNull { it.id == i } }

    /**
     * Whether a server takes part in pinging and selection: in the auto pool when auto-visibility is
     * on, otherwise not hidden by the user.
     */
    fun isActive(s: Server, settings: AppSettings = _settings.value, auto: AutoState = _auto.value): Boolean =
        if (settings.autoActive) s.id in auto.pool else !s.disabled

    fun isActive(id: String): Boolean = server(id)?.let { isActive(it) } == true

    /** Servers that take part in pinging and selection. */
    val activeServers: List<Server> get() = _servers.value.filter { isActive(it) }

    fun updateAuto(f: (AutoState) -> AutoState) {
        _auto.update(f)
        write("auto.json", AutoState.serializer()) { _auto.value }
    }

    /** The server's own name: the user's, else the one from the link (without a leading flag). */
    fun realName(s: Server): String = s.customName ?: Countries.stripFlag(s.name)

    /** Sets the user's name for a server; blank brings back the name from the link. */
    fun renameServer(id: String, name: String) {
        val n = name.trim().takeIf { it.isNotEmpty() }
        _servers.update { list -> list.map { if (it.id == id) it.copy(customName = n?.takeIf { v -> v != Countries.stripFlag(it.name) }) else it } }
        saveServers()
    }

    private class NameCache(val servers: List<Server>, val groups: List<Group>, val subs: List<Subscription>, val names: Map<String, String>)

    @Volatile
    private var nameCache: NameCache? = null

    /**
     * What the UI shows for a server: its [realName], or "<group> #N" with the generic-names setting
     * (N = position in the user's order within the group, from 1). The real name is never changed.
     */
    fun displayName(s: Server): String {
        val settings = _settings.value
        if (!settings.genericNames) return realName(s)
        // A name the user chose wins over "Group #N" if they want it to.
        if (settings.customOverGeneric && s.customName != null) return s.customName
        val servers = _servers.value
        val groups = _groups.value
        val subs = _subscriptions.value
        val cache = nameCache?.takeIf { it.servers === servers && it.groups === groups && it.subs === subs }
            ?: NameCache(servers, groups, subs, buildMap {
                val byId = groups.associateBy { it.id }
                servers.groupBy(::groupOf).forEach { (gid, members) ->
                    val g = groupName(byId[gid])
                    members.forEachIndexed { i, m -> put(m.id, "$g #${i + 1}") }
                }
            }).also { nameCache = it }
        return cache.names[s.id] ?: realName(s)
    }

    fun setServerDisabled(id: String, disabled: Boolean) {
        _servers.update { list -> list.map { if (it.id == id) it.copy(disabled = disabled) else it } }
        saveServers()
    }

    /** Parses links into servers. Invalid links are reported in the second list. */
    fun buildServers(
        links: List<String>,
        subscriptionId: String?,
        groupId: String = subscription(subscriptionId)?.groupId ?: Group.DEFAULT_ID,
    ): Pair<List<Server>, List<ImportErrors.Issue>> {
        val ok = ArrayList<Server>()
        val errors = ArrayList<ImportErrors.Issue>()
        val seen = HashSet<String>()
        for (link in links) {
            try {
                val p = LinkParser.parse(link)
                var id = idFor(link, subscriptionId)
                var n = 1
                while (!seen.add(id)) id = idFor(link, subscriptionId) + "-" + n++
                ok += Server(
                    id = id,
                    name = p.name,
                    link = link.trim(),
                    protocol = p.protocol,
                    host = p.host,
                    port = p.port,
                    subscriptionId = subscriptionId,
                    groupId = groupId,
                    country = Countries.detect(p.name),
                )
            } catch (e: Exception) {
                errors += ImportErrors.forLink(link, e)
            }
        }
        return ok to errors
    }

    fun addServers(list: List<Server>) {
        _servers.update { old ->
            val ids = old.map { it.id }.toHashSet()
            old + list.filter { it.id !in ids }
        }
        saveServers()
    }

    /** Replaces a subscription's servers, keeping manual country overrides. */
    fun replaceSubscriptionServers(subscriptionId: String, freshList: List<Server>) {
        _servers.update { old ->
            val previous = old.filter { it.subscriptionId == subscriptionId }.associateBy { it.id }
            // A server whose link changed (new key, port…) keeps its id: statistics, name, hiding, place.
            val renamed = SubMath.matchChanged(previous.values.toList(), freshList)
            if (renamed.isNotEmpty()) AppLog.debug(TAG, "subscription update: ${renamed.size} servers changed parameters, kept as the same servers")
            val fresh = freshList.map { s -> renamed[s.id]?.let { s.copy(id = it) } ?: s }
            val merged = fresh.map { s ->
                val p = previous[s.id]
                when {
                    p == null -> s
                    // The name from when it was added stays: a refresh never renames a known server.
                    p.countryManual -> s.copy(name = p.name, country = p.country, countryManual = true, addedAt = p.addedAt, disabled = p.disabled, customName = p.customName)
                    s.country == null -> s.copy(name = p.name, country = p.country, addedAt = p.addedAt, disabled = p.disabled, customName = p.customName)
                    else -> s.copy(name = p.name, addedAt = p.addedAt, disabled = p.disabled, customName = p.customName)
                }
            }
            // Keep the order the user arranged; new servers go to the end of the group.
            val known = previous.keys.toList()
            val ordered = merged.filter { it.id in previous }.sortedBy { known.indexOf(it.id) } +
                merged.filter { it.id !in previous }
            val firstIndex = old.indexOfFirst { it.subscriptionId == subscriptionId }.let { if (it < 0) old.size else it }
            val others = old.filter { it.subscriptionId != subscriptionId }
            others.take(firstIndex.coerceAtMost(others.size)) + ordered + others.drop(firstIndex.coerceAtMost(others.size))
        }
        saveServers()
        prunePings()
    }

    fun removeServer(id: String) {
        _servers.update { list -> list.filterNot { it.id == id } }
        saveServers()
        prunePings()
    }

    fun setCountry(id: String, code: String?, manual: Boolean) {
        _servers.update { list -> list.map { if (it.id == id) it.copy(country = code, countryManual = manual) else it } }
        saveServers()
    }

    fun setCountries(codes: Map<String, String>) {
        if (codes.isEmpty()) return
        _servers.update { list ->
            list.map { s -> codes[s.id]?.takeIf { !s.countryManual }?.let { s.copy(country = it) } ?: s }
        }
        saveServers()
    }

    // ------------------------------------------------------------------ subscriptions

    fun subscription(id: String?): Subscription? = id?.let { i -> _subscriptions.value.firstOrNull { it.id == i } }

    /** What the UI shows for a subscription: the user's name, else the title from the provider. */
    fun subName(s: Subscription): String = s.customName ?: s.name

    /** Sets the user's name for a subscription; blank brings back the provider's title. */
    fun renameSubscription(id: String, name: String) {
        val n = name.trim().takeIf { it.isNotEmpty() }
        _subscriptions.update { list -> list.map { if (it.id == id) it.copy(customName = n?.takeIf { v -> v != it.name }) else it } }
        write("subscriptions.json", subsSer) { _subscriptions.value }
    }

    fun upsertSubscription(sub: Subscription) {
        _subscriptions.update { list ->
            if (list.any { it.id == sub.id }) list.map { if (it.id == sub.id) sub else it } else list + sub
        }
        write("subscriptions.json", subsSer) { _subscriptions.value }
    }

    fun removeSubscription(id: String) {
        val groupId = subscription(id)?.groupId
        _subscriptions.update { list -> list.filterNot { it.id == id } }
        _servers.update { list -> list.filterNot { it.subscriptionId == id } }
        // A group that was created just for this subscription goes away with it.
        _groups.update { list ->
            list.filterNot { g ->
                g.id == groupId && g.id != Group.DEFAULT_ID && g.name == null &&
                    _servers.value.none { it.groupId == g.id } && _subscriptions.value.none { it.groupId == g.id }
            }
        }
        write("subscriptions.json", subsSer) { _subscriptions.value }
        saveGroups()
        saveServers()
        prunePings()
    }

    // ------------------------------------------------------------------ groups

    fun group(id: String?): Group? = id?.let { i -> _groups.value.firstOrNull { it.id == i } }

    fun groupOf(server: Server): String =
        server.groupId ?: subscription(server.subscriptionId)?.groupId ?: Group.DEFAULT_ID

    fun subscriptionsIn(groupId: String): List<Subscription> = _subscriptions.value.filter { it.groupId == groupId }

    /**
     * Display name of a group: the default group is always "Default"; others their own name, or (a group
     * made for a subscription) that subscription's title.
     */
    fun groupName(group: Group?): String = when {
        group == null || group.id == Group.DEFAULT_ID -> app.borderless.Res.s(app.borderless.R.string.own_group)
        else -> group.name ?: subscriptionsIn(group.id).firstOrNull()?.let(::subName) ?: app.borderless.Res.s(app.borderless.R.string.own_group)
    }

    /**
     * Before another subscription joins an existing group, its current name is fixed, so the group is not
     * renamed after the newcomer. (Only a brand-new group takes its subscription's title.)
     */
    fun freezeGroupName(id: String) {
        val g = group(id) ?: return
        if (g.id == Group.DEFAULT_ID || g.name != null) return
        val name = groupName(g)
        _groups.update { list -> list.map { if (it.id == id) it.copy(name = name) else it } }
        saveGroups()
    }

    /** A group named after the subscription that will fill it (name resolved from the subscription). */
    fun createSubscriptionGroup(): Group {
        val g = Group("g-" + java.util.UUID.randomUUID().toString().take(8))
        _groups.update { it + g }
        saveGroups()
        return g
    }

    /**
     * Servers of each group, groups in display order. Inside a group: the servers added by hand first,
     * then each subscription's servers as one block (subscriptions in their order). Within a block the
     * user's order, or, with [order] (auto-sort), that order; servers missing from it (disabled, new)
     * keep theirs at the end of their block.
     */
    fun serversByGroup(
        servers: List<Server> = _servers.value,
        groups: List<Group> = _groups.value,
        order: List<String>? = null,
        subscriptions: List<Subscription> = _subscriptions.value,
    ): List<Pair<Group, List<Server>>> {
        val index = order?.withIndex()?.associate { (i, id) -> id to i }
        val byGroup = servers.groupBy(::groupOf)
        val subRank = subscriptions.withIndex().associate { (i, s) -> s.id to i }
        return groups.map { g ->
            // Hand-added (-1), then subscriptions in their order; a stable sort keeps the order inside a block.
            val members = byGroup[g.id].orEmpty().sortedBy { s -> s.subscriptionId?.let { subRank[it] ?: Int.MAX_VALUE } ?: -1 }
            g to if (index == null) members
            else members.sortedWith(compareBy<Server> { s -> s.subscriptionId?.let { subRank[it] ?: Int.MAX_VALUE } ?: -1 }.thenBy { index[it.id] ?: Int.MAX_VALUE })
        }
    }

    fun createGroup(name: String): Group {
        val g = Group("g-" + java.util.UUID.randomUUID().toString().take(8), name.trim())
        _groups.update { it + g }
        saveGroups()
        return g
    }

    fun renameGroup(id: String, name: String) {
        if (id == Group.DEFAULT_ID) return // the default group keeps its name
        _groups.update { list -> list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { null }) else it } }
        saveGroups()
    }

    /** Deletes a group; its servers and subscriptions move to the default group. */
    fun deleteGroup(id: String) {
        val g = group(id) ?: return
        if (g.id == Group.DEFAULT_ID) return
        _servers.update { list -> list.map { if (it.groupId == id) it.copy(groupId = Group.DEFAULT_ID) else it } }
        _subscriptions.update { list -> list.map { if (it.groupId == id) it.copy(groupId = Group.DEFAULT_ID) else it } }
        _groups.update { list -> list.filterNot { it.id == id } }
        write("subscriptions.json", subsSer) { _subscriptions.value }
        saveServers()
        saveGroups()
    }

    /** Drag and drop: puts group [id] where group [targetId] is. */
    fun moveGroup(id: String, targetId: String) {
        _groups.update { list ->
            val from = list.indexOfFirst { it.id == id }
            val to = list.indexOfFirst { it.id == targetId }
            if (from < 0 || to < 0) list else list.toMutableList().apply { add(to, removeAt(from)) }
        }
        saveGroups()
    }

    /** Drag and drop within a block of a group (see [serversByGroup]): puts server [id] where server [targetId] is. */
    fun moveServer(id: String, targetId: String) {
        _servers.update { list ->
            val from = list.indexOfFirst { it.id == id }
            val to = list.indexOfFirst { it.id == targetId }
            // Only within one block: the group's own servers, or one subscription's.
            if (from < 0 || to < 0 || groupOf(list[from]) != groupOf(list[to]) || list[from].subscriptionId != list[to].subscriptionId) list
            else list.toMutableList().apply { add(to, removeAt(from)) }
        }
        saveServers()
    }

    /**
     * Drag and drop within a group: puts subscription [id] where subscription [targetId] is. Their order
     * in the list is the order the groups screen and the heatmap show their blocks in (see [serversByGroup]).
     */
    fun moveSubscriptionOrder(id: String, targetId: String) {
        _subscriptions.update { list ->
            val from = list.indexOfFirst { it.id == id }
            val to = list.indexOfFirst { it.id == targetId }
            // Only within one group: a subscription changes group with [moveSubscription].
            if (from < 0 || to < 0 || list[from].groupId != list[to].groupId) list
            else list.toMutableList().apply { add(to, removeAt(from)) }
        }
        write("subscriptions.json", subsSer) { _subscriptions.value }
    }

    /**
     * Moves a subscription with all its servers to another group. The group it leaves keeps its name
     * (or goes away, if it was made just for this subscription and is empty now).
     */
    fun moveSubscription(id: String, groupId: String) {
        val sub = subscription(id) ?: return
        val from = sub.groupId ?: Group.DEFAULT_ID
        if (from == groupId) return
        freezeGroupName(groupId)
        if (subscriptionsIn(from).size > 1 || _servers.value.any { groupOf(it) == from && it.subscriptionId != id }) freezeGroupName(from)
        _subscriptions.update { list -> list.map { if (it.id == id) it.copy(groupId = groupId) else it } }
        _servers.update { list ->
            // Its servers go to the end of the target group, in their order.
            val moved = list.filter { it.subscriptionId == id }.map { it.copy(groupId = groupId) }
            list.filterNot { it.subscriptionId == id } + moved
        }
        _groups.update { list ->
            list.filterNot { g ->
                g.id == from && g.id != Group.DEFAULT_ID && g.name == null &&
                    _servers.value.none { groupOf(it) == g.id } && _subscriptions.value.none { it.groupId == g.id }
            }
        }
        write("subscriptions.json", subsSer) { _subscriptions.value }
        saveServers()
        saveGroups()
    }

    /** This install's random id for subscription panels (see [AppSettings.deviceId]), created on first use. */
    fun deviceId(): String {
        _settings.value.deviceId.takeIf { it.isNotEmpty() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        updateSettings { if (it.deviceId.isEmpty()) it.copy(deviceId = id) else it }
        return _settings.value.deviceId
    }

    /** Only servers added by hand can change group: subscription servers follow their subscription. */
    fun setServerGroup(id: String, groupId: String) {
        _servers.update { list ->
            val srv = list.firstOrNull { it.id == id && it.subscriptionId == null } ?: return@update list
            // Append at the end of the target group.
            list.filterNot { it.id == id } + srv.copy(groupId = groupId)
        }
        saveServers()
    }

    private fun saveGroups() = write("groups.json", groupsSer) { _groups.value }

    // ------------------------------------------------------------------ sharing

    private fun hiddenKeys(subId: String) =
        _servers.value.filter { it.subscriptionId == subId && it.disabled }.map { ShareFormat.key(it.link) }

    // The name travels as the one in use, so the other side sees the same title (as for servers).
    private fun shareSub(s: Subscription) = ShareFormat.Sub(s.url, subName(s), hiddenKeys(s.id))

    fun shareServer(id: String): ShareFormat.Bundle {
        val s = server(id) ?: return ShareFormat.Bundle()
        return ShareFormat.Bundle(groups = listOf(ShareFormat.G(servers = listOf(ShareFormat.S(s.link)))))
    }

    fun shareSubscription(id: String): ShareFormat.Bundle {
        val s = subscription(id) ?: return ShareFormat.Bundle()
        val g = group(s.groupId)
        return ShareFormat.Bundle(groups = listOf(ShareFormat.G(name = g?.name ?: subName(s), subs = listOf(shareSub(s)))))
    }

    /** A group: its own servers (not the ones that come from its subscriptions) and its subscriptions. */
    fun shareGroup(id: String): ShareFormat.Bundle {
        val g = group(id) ?: return ShareFormat.Bundle()
        return ShareFormat.Bundle(groups = listOf(groupBundle(g)))
    }

    fun shareAll(): ShareFormat.Bundle = ShareFormat.Bundle(groups = _groups.value.map(::groupBundle))

    private fun groupBundle(g: Group) = ShareFormat.G(
        name = g.name,
        default = g.id == Group.DEFAULT_ID,
        servers = _servers.value.filter { groupOf(it) == g.id && it.subscriptionId == null }.map { ShareFormat.S(it.link, it.disabled) },
        subs = subscriptionsIn(g.id).map(::shareSub),
    )

    /** Hides the servers of a subscription whose links (without names) are in [keys]. */
    fun hideByKeys(subId: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val set = keys.toSet()
        _servers.update { list -> list.map { if (it.subscriptionId == subId && ShareFormat.key(it.link) in set) it.copy(disabled = true) else it } }
        saveServers()
    }

    // ------------------------------------------------------------------ pings

    /** [probe]: measured by a check of servers (not a ping of the server in use), see [PingRecord.probedAt]. */
    fun recordPing(id: String, ms: Int?, probe: Boolean = false) {
        _pings.update { m ->
            val r = (m[id] ?: PingRecord()).with(ms)
            m + (id to if (probe) r.copy(probedAt = r.at) else r.copy(probedAt = m[id]?.probedAt ?: 0))
        }
        schedulePingSave()
    }

    fun clearPings() {
        _pings.value = emptyMap()
        schedulePingSave()
    }

    private fun prunePings() {
        val ids = _servers.value.map { it.id }.toHashSet()
        _pings.update { m -> m.filterKeys { it in ids } }
        schedulePingSave()
    }

    private var pingSaveJob: Job? = null

    @Synchronized
    private fun schedulePingSave() {
        if (pingSaveJob?.isActive == true) return
        pingSaveJob = scope.launch {
            delay(5_000)
            write("pings.json", pingsSer) { _pings.value }
        }
    }

    // ------------------------------------------------------------------ io

    private fun saveServers() = write("servers.json", serversSer) { _servers.value }

    private fun idFor(link: String, subscriptionId: String?): String {
        val core = if (link.trimStart().startsWith("{")) link else link.substringBefore('#')
        val digest = MessageDigest.getInstance("SHA-1").digest(((subscriptionId ?: "") + "|" + core.trim()).toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads a data file. If it cannot be parsed it is kept aside as `<name>.broken-<time>` (so the
     * next save does not overwrite the user's data for good) and the problem is reported.
     */
    private fun <T> read(name: String, ser: KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            val (bytes, _) = Crypto.open(f.readBytes(), name)
            json.decodeFromString(ser, String(bytes))
        } catch (e: Exception) {
            Log.w(TAG, "failed to read $name", e)
            val backup = File(dir, "$name.broken-${System.currentTimeMillis()}")
            runCatching { f.copyTo(backup, overwrite = true) }
            Errors.report(e, "reading $name", app.borderless.Res.s(app.borderless.R.string.err_data_file, name, backup.name))
            null
        }
    }

    private val writeLock = Any()

    /** Writes the value current at execution time, so out-of-order launches never persist stale data. */
    private fun <T> write(name: String, ser: KSerializer<T>, value: () -> T) {
        scope.launch {
            synchronized(writeLock) {
                try {
                    val tmp = File(dir, "$name.tmp")
                    // Encrypted and bound to the file name (see Crypto): cannot be read or swapped without the key.
                    tmp.writeBytes(Crypto.seal(json.encodeToString(ser, value()).toByteArray(), name))
                    tmp.renameTo(File(dir, name))
                    Activity.count(Activity.Kind.FILE_WRITE)
                } catch (e: Exception) {
                    Log.w(TAG, "failed to write $name", e)
                    Errors.report(e, "saving $name", app.borderless.Res.s(app.borderless.R.string.err_save, name))
                }
            }
        }
    }
}
