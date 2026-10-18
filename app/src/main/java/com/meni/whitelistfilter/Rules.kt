package com.meni.whitelistfilter

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class Mode(val title: String) {
    BLOCK("חסום"),
    WHITELIST("רשימה לבנה"),
    OPEN("פתוח")
}

data class AppRule(
    val mode: Mode = Mode.WHITELIST,
    val domains: Set<String> = emptySet()
)

data class RulesData(
    val global: Set<String> = emptySet(),
    val apps: Map<String, AppRule> = emptyMap()
)

object Rules {
    private const val PREFS = "rules"
    private const val KEY = "json"

    private lateinit var prefs: SharedPreferences
    private lateinit var pm: PackageManager
    private val _data = MutableStateFlow(RulesData())
    val data: StateFlow<RulesData> = _data

    private val uidPackages = ConcurrentHashMap<Int, List<String>>()
    private val uidLabels = ConcurrentHashMap<Int, String>()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        pm = context.applicationContext.packageManager
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _data.value = load(prefs.getString(KEY, null))
    }

    // ---------- זיהוי אפליקציות ----------

    fun packagesForUid(uid: Int): List<String> {
        if (uid < 0) return emptyList()
        return uidPackages.getOrPut(uid) {
            try {
                pm.getPackagesForUid(uid)?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun packageForUid(uid: Int): String? = packagesForUid(uid).firstOrNull()

    fun labelForUid(uid: Int): String = uidLabels.getOrPut(uid) {
        val pkg = packageForUid(uid)
        when {
            pkg != null -> labelForPackage(pkg)
            uid < 0 -> "לא ידוע"
            else -> "מערכת ($uid)"
        }
    }

    fun labelForPackage(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    // ---------- החלטות ----------

    fun ruleForPackage(pkg: String): AppRule = _data.value.apps[pkg] ?: AppRule()

    fun ruleForUid(uid: Int): AppRule {
        val apps = _data.value.apps
        return packagesForUid(uid).firstNotNullOfOrNull { apps[it] } ?: AppRule()
    }

    private fun matches(set: Set<String>, domain: String): Boolean {
        val d = normalize(domain)
        return set.any { d == it || d.endsWith(".$it") }
    }

    /** האם האפליקציה הזו רשאית לגשת לדומיין */
    fun isAllowed(uid: Int, domain: String): Boolean {
        val rule = ruleForUid(uid)
        return when (rule.mode) {
            Mode.BLOCK -> false
            Mode.OPEN -> true
            Mode.WHITELIST -> matches(_data.value.global, domain) || matches(rule.domains, domain)
        }
    }

    /** חיבור לכתובת IP: מותר אם אחד הדומיינים שהפנו לכתובת הזו מותר לאפליקציה */
    fun connectionAllowed(uid: Int, domains: Set<String>): Boolean {
        return when (ruleForUid(uid).mode) {
            Mode.BLOCK -> false
            Mode.OPEN -> true
            Mode.WHITELIST -> domains.any { isAllowed(uid, it) }
        }
    }

    /** שאילתת DNS. אם לא ידוע מי שאל — מותר אם יש אפליקציה כלשהי שמותר לה */
    fun dnsAllowed(uid: Int, domain: String): Boolean {
        if (uid >= 10000 && packagesForUid(uid).isNotEmpty()) return isAllowed(uid, domain)
        val d = _data.value
        if (matches(d.global, domain)) return true
        return d.apps.values.any {
            it.mode == Mode.OPEN || (it.mode == Mode.WHITELIST && matches(it.domains, domain))
        }
    }

    // ---------- עריכה ----------

    fun addGlobal(raw: String) {
        val d = normalize(raw)
        if (!valid(d)) return
        change { it.copy(global = it.global + d) }
    }

    fun removeGlobal(domain: String) = change { it.copy(global = it.global - domain) }

    fun setMode(pkg: String, mode: Mode) = change {
        it.copy(apps = it.apps + (pkg to (it.apps[pkg] ?: AppRule()).copy(mode = mode)))
    }

    fun addAppDomain(pkg: String, raw: String) {
        val d = normalize(raw)
        if (!valid(d)) return
        change {
            val r = it.apps[pkg] ?: AppRule()
            it.copy(apps = it.apps + (pkg to r.copy(domains = r.domains + d)))
        }
    }

    fun removeAppDomain(pkg: String, domain: String) = change {
        val r = it.apps[pkg] ?: AppRule()
        it.copy(apps = it.apps + (pkg to r.copy(domains = r.domains - domain)))
    }

    private fun change(block: (RulesData) -> RulesData) {
        _data.update(block)
        prefs.edit().putString(KEY, save(_data.value)).apply()
    }

    private fun valid(d: String) = d.isNotEmpty() && d.contains('.') && !d.contains(' ')

    fun normalize(raw: String): String = raw.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore("/").substringBefore(":")
        .removePrefix("www.").trimEnd('.')

    // ---------- שמירה ----------

    private fun save(d: RulesData): String {
        val apps = JSONObject()
        d.apps.forEach { (pkg, r) ->
            apps.put(pkg, JSONObject().put("mode", r.mode.name).put("domains", JSONArray(r.domains.toList())))
        }
        return JSONObject().put("global", JSONArray(d.global.toList())).put("apps", apps).toString()
    }

    private fun load(json: String?): RulesData {
        if (json == null) return RulesData()
        return try {
            val o = JSONObject(json)
            val global = o.optJSONArray("global").toSet()
            val apps = HashMap<String, AppRule>()
            val a = o.optJSONObject("apps") ?: JSONObject()
            a.keys().forEach { pkg ->
                val r = a.getJSONObject(pkg)
                val mode = try {
                    Mode.valueOf(r.optString("mode", Mode.WHITELIST.name))
                } catch (_: Exception) {
                    Mode.WHITELIST
                }
                apps[pkg] = AppRule(mode, r.optJSONArray("domains").toSet())
            }
            RulesData(global, apps)
        } catch (_: Exception) {
            RulesData()
        }
    }

    private fun JSONArray?.toSet(): Set<String> {
        if (this == null) return emptySet()
        val s = HashSet<String>()
        for (i in 0 until length()) s += getString(i)
        return s
    }
}
