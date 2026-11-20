package app.borderless.data

import app.borderless.S
import app.borderless.platform.Lock
import app.borderless.platform.deleteFile
import app.borderless.platform.hex
import app.borderless.platform.hmacSha256
import app.borderless.platform.listDir
import app.borderless.platform.nowMs
import app.borderless.platform.text
import app.borderless.platform.urlHost
import app.borderless.platform.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * How every data file is read and written. Old files must always stay readable by newer versions:
 * unknown keys (removed fields) are ignored, unknown enum values fall back to defaults, and new fields
 * need defaults. Tested against files written by every release (`CompatTest`, `test/resources/compat`).
 */

/** Persistent app state. All mutations go through here; files are written asynchronously. */
object Repo {
    private const val TAG = "Repo"
    private lateinit var store: DataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val json = StoreJson

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    private val _pings = MutableStateFlow<Map<String, PingRecord>>(emptyMap())
    private val _settings = MutableStateFlow(AppSettings())
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _auto = MutableStateFlow(AutoState())
    private val _zones = MutableStateFlow<List<CustomZone>>(emptyList())

    val servers: StateFlow<List<Server>> = _servers.asStateFlow()
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()
    val pings: StateFlow<Map<String, PingRecord>> = _pings.asStateFlow()
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Groups in display order. */
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    val auto: StateFlow<AutoState> = _auto.asStateFlow()

    /** Domain zones the user made (see [CustomZone]); they take part like regions.json's ([Regions.custom]). */
    val zones: StateFlow<List<CustomZone>> = _zones.asStateFlow()

    /** Servers that take part in pinging and selection, in list order; updates with visibility changes. */
    val visible: StateFlow<List<Server>> = combine(_servers, _settings, _auto) { list, s, a -> list.filter { isActive(it, s, a) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val serversSer = ListSerializer(Server.serializer())
    private val subsSer = ListSerializer(Subscription.serializer())
    private val pingsSer = MapSerializer(String.serializer(), PingRecord.serializer())
    private val groupsSer = ListSerializer(Group.serializer())
    private val zonesSer = ListSerializer(CustomZone.serializer())

    @Volatile
    private var initialized = false

    private val initLock = Lock()

    /** Once at start, with the platform's [DataStore]. */
    fun init(dataStore: DataStore) = initLock.withLock {
        if (initialized) return
        store = dataStore
        _servers.value = read("servers.json", serversSer) ?: emptyList()
        _subscriptions.value = read("subscriptions.json", subsSer) ?: emptyList()
        _pings.value = read("pings.json", pingsSer) ?: emptyMap()
        _settings.value = read("settings.json", AppSettings.serializer()) ?: AppSettings.fresh()
        _groups.value = read("groups.json", groupsSer) ?: emptyList()
        _auto.value = read("auto.json", AutoState.serializer()) ?: AutoState()
        _zones.value = read("zones.json", zonesSer) ?: emptyList()
        Regions.custom = _zones.value.map(CustomZone::toRegion)
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
        // A group made for a subscription (name == null) doesn't outlive its content: with the subscription
        // and its servers gone it is dropped too — what removeSubscription does on the per-item path; the
        // whole-list replace (replaceAll, the Mac app's save) reaches the same state through here.
        val lean = _groups.value.filterNot { g ->
            g.id != Group.DEFAULT_ID && g.name == null &&
                _servers.value.none { groupOf(it) == g.id } && _subscriptions.value.none { it.groupId == g.id }
        }
        if (lean.size != _groups.value.size) {
            _groups.value = lean
            saveGroups()
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
     * Whether a server takes part in pinging and selection: with countries chosen, their servers not hidden by the
     * user (auto-visibility doesn't apply then); otherwise in the auto pool when auto-visibility is on, else not
     * hidden by the user.
     */
    fun isActive(s: Server, settings: AppSettings = _settings.value, auto: AutoState = _auto.value): Boolean = when {
        settings.avoidInsecure && Insecurity.of(s).isNotEmpty() -> false
        settings.countries.isNotEmpty() -> Countries.of(s) in settings.countries && !s.disabled
        // The pool manages ordinary servers only; limited-network servers stay as the user shows them (else none would
        // ever be active with auto-visibility on and the engine could never fall back to them).
        settings.autoActive -> if (s.limited) !s.disabled else s.id in auto.pool
        else -> !s.disabled
    }

    fun isActive(id: String): Boolean = server(id)?.let { isActive(it) } == true

    /** Servers that take part in pinging and selection. */
    val activeServers: List<Server> get() = _servers.value.filter { isActive(it) }

    /**
     * Active servers reached directly (not limited-network ones through the core): the ones ordinary scans probe and the
     * engine prefers. Limited-network servers ([Server.limited]) are kept out of scans (each needs its core running) and
     * chosen only as a last resort (`core/Limited.kt`, `core/Engine.kt`).
     */
    val activeOrdinary: List<Server> get() = _servers.value.filter { isActive(it) && !it.limited }

    /** Active limited-network servers, best (by recent success) first. */
    val limitedServers: List<Server> get() = _servers.value.filter { isActive(it) && it.limited }

    fun updateAuto(f: (AutoState) -> AutoState) {
        _auto.update(f)
        write("auto.json", AutoState.serializer()) { _auto.value }
    }

    /** The server's own name: the user's, else the one from the link (without a leading flag). */
    fun realName(s: Server): String = s.customName ?: Countries.stripFlag(s.name)

    /** Sets the user's name for a server; blank brings back the name from the link. */
    fun renameServer(id: String, name: String) {
        val before = server(id) ?: return
        val n = name.trim().takeIf { it.isNotEmpty() }
        _servers.update { list -> list.map { if (it.id == id) it.copy(customName = n?.takeIf { v -> v != Countries.stripFlag(it.name) }) else it } }
        saveServers()
        // The log keeps the whole story of a server: what it was called and what it became.
        val after = server(id) ?: return
        val was = realName(before)
        val now = realName(after)
        if (was != now) AppLog.event(LText.of(S.ev_server_renamed, was, now))
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
        if (settings.customOverGeneric) s.customName?.let { return it }
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

    /** Limited-network servers: streams (0 = automatic), the device id sent to the server (empty = default), and whether to ignore its stream limit. */
    fun setLimitedOptions(id: String, streams: Int, device: String, ignoreLimit: Boolean = false) {
        val before = server(id) ?: return
        _servers.update { list -> list.map { if (it.id == id) it.copy(limitedStreams = streams.coerceIn(0, LimitedPower.MAX_STREAMS), limitedDevice = device.trim(), limitedIgnoreLimit = ignoreLimit) else it } }
        saveServers()
        val after = server(id) ?: return
        val streamsText = if (after.limitedStreams == 0) LText.of(S.limited_auto) else LText.of(S.limited_n_streams, after.limitedStreams)
        if (before.limitedStreams != after.limitedStreams || before.limitedIgnoreLimit != after.limitedIgnoreLimit) {
            AppLog.event(LText.concat(
                LText.of(S.ev_server_power, realName(after), streamsText),
                if (after.limitedIgnoreLimit) LText.of(S.ev_server_power_ignore) else "",
            ))
        }
        if (before.limitedDevice != after.limitedDevice) {
            // The id itself is a credential-like value: only whether it is set, not what it is.
            AppLog.debug(TAG, "server power: ${realName(after)}, device id ${if (after.limitedDevice.isEmpty()) "cleared" else "set"}")
        }
    }

    fun setServerDisabled(id: String, disabled: Boolean) {
        val before = server(id) ?: return
        _servers.update { list -> list.map { if (it.id == id) it.copy(disabled = disabled) else it } }
        saveServers()
        if (before.disabled != disabled) {
            val after = server(id) ?: return
            AppLog.event(LText.of(if (disabled) S.ev_server_hidden else S.ev_server_shown, realName(after)))
        }
    }

    /** Parses links into servers. Invalid links are reported in the second list. */
    fun buildServers(
        links: List<String>,
        subscriptionId: String?,
        groupId: String = subscription(subscriptionId)?.groupId ?: Group.DEFAULT_ID,
    ): Pair<List<Server>, List<ImportIssue>> = ServerBuild.build(links, subscriptionId, groupId)

    /** Adds the servers whose id isn't known yet ([Server.id] dedupes); returns how many really joined. */
    fun addServers(list: List<Server>): Int {
        var added = 0
        _servers.update { old ->
            val ids = old.map { it.id }.toHashSet()
            val fresh = list.filter { it.id !in ids }
            added = fresh.size
            old + fresh
        }
        saveServers()
        // The import already logs its own event (with counts): here the detailed log gets what exactly came in.
        if (added > 0) AppLog.debug(TAG, "added $added server(s): " + list.take(10).joinToString { "${it.protocol} · ${it.name}" } + if (list.size > 10) " …" else "")
        return added
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
                if (p == null) s else s.copy(name = p.name, country = p.country,
                    countryManual = p.countryManual, countryByIp = p.countryByIp, countryLookupVersion = p.countryLookupVersion, addedAt = p.addedAt,
                    disabled = p.disabled, customName = p.customName, limitedStreams = p.limitedStreams,
                    limitedDevice = p.limitedDevice, limitedIgnoreLimit = p.limitedIgnoreLimit)
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
        val gone = server(id)
        _servers.update { list -> list.filterNot { it.id == id } }
        saveServers()
        prunePings()
        if (gone != null) AppLog.event(LText.of(S.ev_server_removed, realName(gone), gone.protocol), AppLog.Kind.WARN)
    }

    fun setCountry(id: String, code: String?, manual: Boolean) {
        val before = server(id) ?: return
        _servers.update { list -> list.map { if (it.id == id) it.copy(country = code, countryManual = manual, countryByIp = false, countryLookupVersion = 0) else it } }
        saveServers()
        val after = server(id) ?: return
        if (before.country != after.country || before.countryManual != after.countryManual) {
            val label = if (after.country == null) LText.of(S.country_auto) else LText.raw(Countries.name(after.country))
            AppLog.event(LText.of(S.ev_server_country, realName(after), label))
        }
    }

    fun setCountries(codes: Map<String, String>) {
        if (codes.isEmpty()) return
        _servers.update { list ->
            list.map { s -> codes[s.id]?.let { Countries.fromIp(s, it) } ?: s }
        }
        saveServers()
        // Detected countries (by the servers' names or through a server): how many, not which ones (the log names servers anyway).
        AppLog.debug(TAG, "countries detected for ${codes.size} server(s)")
    }

    /** Only used after IP lookup is unavailable or has failed; never overwrites a successful IP result. */
    fun applyCountryNameFallbacks() {
        var changed = false
        _servers.update { list -> list.map { s -> Countries.nameFallback(s).also { if (it != s) changed = true } } }
        if (changed) saveServers()
    }

    // ------------------------------------------------------------------ subscriptions

    fun subscription(id: String?): Subscription? = id?.let { i -> _subscriptions.value.firstOrNull { it.id == i } }

    /** What the UI shows for a subscription: the user's name, else the title from the provider. */
    fun subName(s: Subscription): String = s.customName ?: s.name

    /** Sets the user's name for a subscription; blank brings back the provider's title. */
    fun renameSubscription(id: String, name: String) {
        val before = subscription(id) ?: return
        val n = name.trim().takeIf { it.isNotEmpty() }
        _subscriptions.update { list -> list.map { if (it.id == id) it.copy(customName = n?.takeIf { v -> v != it.name }) else it } }
        write("subscriptions.json", subsSer) { _subscriptions.value }
        val after = subscription(id) ?: return
        val was = subName(before)
        val now = subName(after)
        if (was != now) AppLog.event(LText.of(S.ev_sub_renamed, was, now))
    }

    fun upsertSubscription(sub: Subscription) {
        val fresh = subscription(sub.id) == null
        _subscriptions.update { list ->
            if (list.any { it.id == sub.id }) list.map { if (it.id == sub.id) sub else it } else list + sub
        }
        write("subscriptions.json", subsSer) { _subscriptions.value }
        if (fresh) AppLog.event(LText.of(S.ev_sub_added, subName(sub)))
        else AppLog.debug(TAG, "subscription updated: ${subName(sub)} (${sub.name})")
    }

    fun removeSubscription(id: String) {
        val gone = subscription(id)
        val groupId = gone?.groupId
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
        if (gone != null) AppLog.event(LText.of(S.ev_sub_removed, subName(gone)), AppLog.Kind.WARN)
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
        group == null || group.id == Group.DEFAULT_ID -> text(S.own_group)
        else -> group.name ?: subscriptionsIn(group.id).firstOrNull()?.let(::subName) ?: text(S.own_group)
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
        val g = Group("g-" + Uuid.random().toHexString().take(8))
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
        val g = Group("g-" + Uuid.random().toHexString().take(8), name.trim())
        _groups.update { it + g }
        saveGroups()
        AppLog.event(LText.of(S.ev_group_added, groupName(g)))
        return g
    }

    fun renameGroup(id: String, name: String) {
        if (id == Group.DEFAULT_ID) return // the default group keeps its name
        val before = groupName(group(id))
        _groups.update { list -> list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { null }) else it } }
        saveGroups()
        val after = groupName(group(id))
        if (before != after) AppLog.event(LText.of(S.ev_group_renamed, before, after))
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
        AppLog.event(LText.of(S.ev_group_removed, groupName(g), groupName(group(Group.DEFAULT_ID))), AppLog.Kind.WARN)
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
        AppLog.event(LText.of(S.ev_sub_moved, subName(sub), groupName(group(groupId))))
        saveGroups()
    }

    /** This install's random id for subscription panels (see [AppSettings.deviceId]), created on first use. */
    fun deviceId(): String {
        _settings.value.deviceId.takeIf { it.isNotEmpty() }?.let { return it }
        val id = Uuid.random().toHexString()
        updateSettings { if (it.deviceId.isEmpty()) it.copy(deviceId = id) else it }
        return _settings.value.deviceId
    }

    /**
     * The device id for a new subscription at [url]: HMAC-SHA256 of its host keyed with [deviceId], so every provider
     * sees a stable id of its own and two providers can't tell they serve the same phone.
     */
    fun deviceIdFor(url: String): String {
        val host = urlHost(url)?.lowercase() ?: url
        return hmacSha256(deviceId().encodeToByteArray(), host.encodeToByteArray()).copyOf(16).hex()
    }

    /** Only servers added by hand can change group: subscription servers follow their subscription. */
    fun setServerGroup(id: String, groupId: String) {
        var moved: Server? = null
        _servers.update { list ->
            val srv = list.firstOrNull { it.id == id && it.subscriptionId == null } ?: return@update list
            if (groupOf(srv) == groupId) return@update list
            moved = srv
            // Append at the end of the target group.
            list.filterNot { it.id == id } + srv.copy(groupId = groupId)
        }
        saveServers()
        moved?.let { AppLog.event(LText.of(S.ev_server_moved, realName(it), groupName(group(groupId)))) }
    }

    private fun saveGroups() = write("groups.json", groupsSer) { _groups.value }

    // ------------------------------------------------------------------ the user's domain zones

    fun zone(id: String): CustomZone? = _zones.value.firstOrNull { it.id == id }

    /** Adds [z] (switched on) or replaces the zone with its id. Its `.dat` files no entry names any more go. */
    fun saveZone(z: CustomZone, enable: Boolean = false) {
        val used = (z.direct + z.server).filter { it.startsWith("ext:") }.map { it.removePrefix("ext:").substringBefore(':') }.toSet()
        val fresh = _zones.value.none { it.id == z.id }
        zoneFiles(z.id).filter { it.substringAfterLast('/') !in used }.forEach { deleteFile(it) }
        _zones.update { list -> if (list.any { it.id == z.id }) list.map { if (it.id == z.id) z else it } else list + z }
        Regions.custom = _zones.value.map(CustomZone::toRegion)
        write("zones.json", zonesSer) { _zones.value }
        if (enable) updateSettings { it.copy(regions = it.regions + z.id) }
        // What the zone does to traffic: counts, not the entries themselves (the files hold those).
        AppLog.debug(TAG, "zone ${z.name} ${if (fresh) "created" else "saved"}: ${z.direct.size} direct, ${z.server.size} via servers, " +
            "${z.apps.size} app(s), on: ${enable || z.id in _settings.value.regions}")
    }

    /**
     * The zone's membership in the enabled routing zones ([AppSettings.regions]) — what the toggle in the
     * zone list does. saveZone's `enable` only ever switches a zone on; this one switches it off too.
     */
    fun setZoneEnabled(id: String, on: Boolean) {
        updateSettings { it.copy(regions = if (on) it.regions + id else it.regions - id) }
    }

    /** The paths of the `.dat` files kept for a zone (DatFile), in the folder the core reads lists from. */
    fun zoneFiles(id: String): List<String> = GeoFile.dir?.let { dir ->
        listDir(dir).filter { it.startsWith("$id-") && it.endsWith(".dat") }.map { "$dir/$it" }
    }.orEmpty()

    fun deleteZone(id: String) {
        val gone = zone(id)
        zoneFiles(id).forEach { deleteFile(it) }
        _zones.update { list -> list.filterNot { it.id == id } }
        Regions.custom = _zones.value.map(CustomZone::toRegion)
        write("zones.json", zonesSer) { _zones.value }
        updateSettings { it.copy(regions = it.regions - id) }
        AppLog.debug(TAG, "zone ${gone?.name ?: id} deleted")
    }

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
        // An import carried the hidden servers of another phone: the user should find why some are hidden here.
        AppLog.debug(TAG, "import: ${set.size} server(s) of subscription ${subscription(subId)?.let(::subName) ?: subId} hidden")
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

    /**
     * The detailed core log records every destination (the access log): it doesn't stay on for good — a day after it
     * was turned on it turns itself off (checked at app start and hourly by the engine).
     */
    fun expireVerbose() {
        val s = _settings.value
        if (!s.verboseLog) return
        val now = nowMs()
        if (s.verboseSince == 0L) updateSettings { it.copy(verboseSince = now) }
        else if (now - s.verboseSince > VERBOSE_MS) {
            updateSettings { it.copy(verboseLog = false, verboseSince = 0) }
            AppLog.event(LText.of(S.ev_verbose_off))
        }
    }

    private const val VERBOSE_MS = 24 * 3_600_000L

    /** A health check of [id] through the running core succeeded and took [ms] in all. */
    fun recordCheck(id: String, ms: Int) {
        _pings.update { m -> m[id]?.let { r -> m + (id to r.copy(checks = (r.checks + ms).takeLast(5))) } ?: m }
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
    private val pingSaveLock = Lock()

    private fun schedulePingSave() = pingSaveLock.withLock {
        if (pingSaveJob?.isActive == true) return
        pingSaveJob = scope.launch {
            delay(5_000)
            write("pings.json", pingsSer) { _pings.value }
        }
    }

    // ------------------------------------------------------------------ io

    /**
     * The whole lists at once (the Mac app edits them as one and sends them back; the service merges what the app
     * doesn't know into them first). Groups are checked as at start; pings of servers that went are dropped.
     */
    fun replaceAll(servers: List<Server>, groups: List<Group>, subscriptions: List<Subscription>) {
        _groups.value = groups
        _subscriptions.value = subscriptions
        _servers.value = servers
        saveGroups()
        write("subscriptions.json", subsSer) { _subscriptions.value }
        saveServers()
        checkGroups()
        prunePings()
    }

    /** Sets [Server.limitedDevice] of the given servers without logging (a data migration, not the user's change). */
    fun setLimitedDevicesQuietly(ids: Map<String, String>) {
        if (ids.isEmpty()) return
        _servers.update { list -> list.map { s -> ids[s.id]?.let { s.copy(limitedDevice = it) } ?: s } }
        saveServers()
    }

    private fun saveServers() = write("servers.json", serversSer) { _servers.value }


    /**
     * Reads a data file. If it cannot be parsed it is kept aside as `<name>.broken-<time>` (so the
     * next save does not overwrite the user's data for good) and the problem is reported.
     */
    private fun <T> read(name: String, ser: KSerializer<T>): T? = try {
        store.read(name)?.let { json.decodeFromString(ser, it.decodeToString()) }
    } catch (e: Exception) {
        AppLog.debug(TAG, "failed to read $name: $e")
        val backup = runCatching { store.keepAside(name) }.getOrDefault("")
        Errors.report(e, "reading $name", text(S.err_data_file, name, backup))
        null
    }

    private val writeLock = Lock()

    /** Write jobs in flight or queued ([awaitWrites] joins them); pruned on completion. */
    private val writeJobsLock = Lock()
    private val writeJobs = ArrayList<Job>()

    /** Writes the value current at execution time, so out-of-order launches never persist stale data. */
    private fun <T> write(name: String, ser: KSerializer<T>, value: () -> T) {
        // Stored data that can't be opened now must not be replaced (see DataStore.locked).
        if (store.locked) return
        val job = scope.launch {
            writeLock.withLock {
                try {
                    store.write(name, json.encodeToString(ser, value()).encodeToByteArray())
                    Work.count(WorkKind.FILE_WRITE)
                } catch (e: Exception) {
                    AppLog.debug(TAG, "failed to write $name: $e")
                    Errors.report(e, "saving $name", text(S.err_save, name))
                }
            }
        }
        writeJobsLock.withLock { writeJobs.add(job) }
        job.invokeOnCompletion { writeJobsLock.withLock { writeJobs.remove(job) } }
    }

    /**
     * Suspends until every write queued by now has run (a failed one already reported itself inside [write],
     * so a join is enough). Only the Mac's service waits — its socket reply of "stored" would otherwise go
     * out while the file write still sits in the queue, and a crash in between loses data the app was told
     * is saved; the Android UI trusts the queue (writes stay ordered by [writeLock] and read back the
     * current value), so nothing calls this there.
     */
    suspend fun awaitWrites() {
        writeJobsLock.withLock { writeJobs.toList() }.forEach { it.join() }
    }
}
