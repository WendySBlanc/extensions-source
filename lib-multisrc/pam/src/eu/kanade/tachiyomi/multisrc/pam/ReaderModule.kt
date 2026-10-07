package eu.kanade.tachiyomi.multisrc.pam

import android.util.Base64
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import keiyoushi.network.get
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The reader's signer as shipped by the site. Each build renames the WASM exports and reshuffles
 * the tables of the imports that unmask the signer's secret, and sites rebuild every few days,
 * so all of it is read from the site's own bundle instead of being shipped with the extension.
 */
internal class ReaderModule(
    val module: WasmModule,
    /** Reader-side function name (`signAttestation`, `malloc`, ...) to export name. */
    val exports: Map<String, String>,
    /** The only host functions the signer may import, as module and name. */
    val resizeImport: Pair<String, String>,
    val unmaskImports: Map<Pair<String, String>, Unmask>,
) {
    fun export(name: String): String = exports[name] ?: throw IOException("Reader export $name missing")
}

/**
 * Rewrites a 64-byte block in place. Each step picks a byte through [permutation] and folds a
 * table-driven value into a running byte; the tables, the fold and the loop shape are reshuffled
 * per build, so all of it is read from the site's glue.
 */
internal class Unmask(
    val permutation: IntArray,
    val xor: IntArray,
    val add: IntArray,
    val rotate: IntArray? = null,
    val operation: IntArray? = null,
    val rounds: Int = 1,
    val reverse: Boolean = false,
    val feedback: UnmaskFeedback = UnmaskFeedback.NONE,
    val feedbackSeed: Int = 0,
)

internal enum class UnmaskFeedback {
    NONE,
    XOR,
    ADD_ROTATE_ONE,
}

internal suspend fun OkHttpClient.fetchReaderModule(baseUrl: String, headers: Headers): ReaderModule {
    suspend fun asset(name: String): String = get("$baseUrl/build/assets/$name", headers).use { it.body.string() }

    val home = get(baseUrl, headers).use { it.body.string() }
    val entry = ENTRY_REGEX.find(home)?.groupValues?.get(1) ?: throw IOException("Reader entry script not found")
    val entryScript = asset(entry)
    val reader = READER_CHUNK_REGEX.findAll(entryScript).lastOrNull()?.groupValues
        ?: throw IOException("Reader chunk not found")

    // Vite lists every chunk a page loads, transitively, next to the page's loader.
    val entryDeps = MAP_DEPS_REGEX.find(entryScript)?.groupValues?.get(1)
        ?.let { deps -> DEP_REGEX.findAll(deps).map { it.groupValues[1] }.toList() }
        .orEmpty()
    val readerDeps = reader[2].split(',').filter(String::isNotEmpty).mapNotNull { entryDeps.getOrNull(it.toInt()) }

    // The export name map lives in a chunk shared by every reader version.
    val shared = (IMPORT_REGEX.findAll(asset(reader[1])).map { it.groupValues[1] } + readerDeps)
        .filter { it.endsWith(".js") && it != entry }
        .distinct()
        .toList()
        .firstNotNullOfOrNull { name -> asset(name).takeIf { "freeBuffer:\"" in it } }
        ?: throw IOException("Reader signer bindings not found")
    val semantic = EXPORT_MAP_REGEX.find(shared)?.value
        ?.let { map -> PAIR_REGEX.findAll(map).associate { it.groupValues[1] to it.groupValues[2] } }
        ?: throw IOException("Reader export map not found")

    val glue = MAP_DEPS_REGEX.find(shared)?.groupValues?.get(1)
        ?.let { deps -> DEP_REGEX.findAll(deps).map { it.groupValues[1] }.toList() }
        .orEmpty()
        .filter { it.endsWith(".js") }
        .firstNotNullOfOrNull { name -> asset(name).takeIf { WASM_PREFIX in it } }
        ?: throw IOException("Reader signer module not found")

    val glueNames = GLUE_EXPORT_REGEX.findAll(glue).associate { it.groupValues[1] to it.groupValues[2] }
    val exports = buildMap {
        semantic.forEach { (name, glueName) -> glueNames[glueName]?.let { put(name, it) } }
        glueNames["_malloc"]?.let { put("malloc", it) }
        CTORS_REGEX.find(glue)?.groupValues?.get(1)?.let { put("ctors", it) }
    }

    val (importObject, resizeName) = RESIZE_IMPORT_REGEX.find(glue)?.destructured
        ?: throw IOException("Unsupported reader signer build (resize import)")
    val importModule = Regex("""var [\w$]+=\{([\w$]+):${Regex.escape(importObject)}\}""").find(glue)?.groupValues?.get(1)
        ?: throw IOException("Unsupported reader signer build (import module)")

    // Builds ship one or more unmask imports, each with its own loop shape and table order.
    val unmasks = buildMap {
        UNMASK_REGEX.findAll(glue).forEach { match ->
            val groups = match.groupValues
            val tables = mapOf(groups[3] to groups[4], groups[5] to groups[6], groups[7] to groups[8])
                .mapValues { (_, values) -> values.split(',').map(String::toInt).toIntArray() }
            if (tables.size != 3 || tables.values.any { it.size != UNMASK_SIZE }) {
                throw IOException("Unsupported reader signer build (legacy tables)")
            }
            put(
                importModule to groups[1],
                Unmask(
                    permutation = tables[groups[11]] ?: throw IOException("Unsupported reader signer build (legacy permutation)"),
                    xor = tables[groups[12]] ?: throw IOException("Unsupported reader signer build (legacy xor)"),
                    add = tables[groups[13]] ?: throw IOException("Unsupported reader signer build (legacy add)"),
                ),
            )
        }

        ADVANCED_UNMASK_FUNCTION_REGEX.findAll(glue).forEach { match ->
            val name = match.groupValues[1]
            val pointer = match.groupValues[2]
            val body = match.groupValues[3]
            put(importModule to name, parseAdvancedUnmask(pointer, body))
        }
    }
    if (unmasks.isEmpty()) throw IOException("Unsupported reader signer build (no unmask imports)")

    val wasm = WASM_REGEX.find(glue)?.groupValues?.get(1) ?: throw IOException("Reader signer module not found")

    return ReaderModule(
        module = Parser.parse(Base64.decode(wasm, Base64.DEFAULT)),
        exports = exports,
        resizeImport = importModule to resizeName,
        unmaskImports = unmasks,
    )
}

private fun parseAdvancedUnmask(pointer: String, body: String): Unmask {
    fun unsupported(stage: String): Nothing = throw IOException("Unsupported reader signer build (advanced $stage)")

    val tables = ADVANCED_TABLE_REGEX.findAll(body).associate { match ->
        match.groupValues[1] to match.groupValues[2].split(',').map(String::toInt).toIntArray()
    }
    if (tables.size != 5 || tables.values.any { it.size != UNMASK_SIZE }) unsupported("tables")

    val roundsMatch = ADVANCED_ROUNDS_REGEX.find(body) ?: unsupported("rounds")
    if (roundsMatch.groupValues[1] != roundsMatch.groupValues[3] || roundsMatch.groupValues[1] != roundsMatch.groupValues[4]) {
        unsupported("round counter")
    }
    val rounds = roundsMatch.groupValues[2].toInt().takeIf { it in 1..16 } ?: unsupported("round count")

    val escapedPointer = Regex.escape(pointer)
    val loop = Regex(
        """for\(var ([\w$]+)=([\w$]+)\.slice\($escapedPointer,$escapedPointer\+64\),([\w$]+)=(\d+),([\w$]+)=(\d+);([^;]+);([\w$]+)(\+\+|--)\)\{""",
    ).find(body) ?: unsupported("loop")
    val block = loop.groupValues[1]
    val memory = loop.groupValues[2]
    val feedbackVariable = loop.groupValues[3]
    val feedbackSeed = loop.groupValues[4].toInt()
    val index = loop.groupValues[5]
    val start = loop.groupValues[6].toInt()
    val condition = loop.groupValues[7]
    if (index != loop.groupValues[8]) unsupported("loop index")
    val reverse = when {
        start == 0 && condition == "$UNMASK_SIZE>$index" && loop.groupValues[9] == "++" -> false
        start == UNMASK_SIZE - 1 && condition == "0<=$index" && loop.groupValues[9] == "--" -> true
        else -> unsupported("loop direction")
    }

    val escapedBlock = Regex.escape(block)
    val escapedIndex = Regex.escape(index)
    val inputMatch = Regex("""var ([\w$]+)=$escapedBlock\[([\w$]+)\[$escapedIndex\]\]""").find(body)
        ?: unsupported("input")
    val input = inputMatch.groupValues[1]
    val permutationName = inputMatch.groupValues[2]
    val escapedInput = Regex.escape(input)

    fun tableName(stage: String, pattern: String): String = Regex(pattern).find(body)?.groupValues?.get(1) ?: unsupported(stage)

    val operationName = tableName("operation table", """0==([\w$]+)\[$escapedIndex\]""")
    val xorName = tableName("xor table", """\($escapedInput\^([\w$]+)\[$escapedIndex\]\)""")
    val addName = tableName("add table", """\)\+([\w$]+)\[$escapedIndex\]&255""")
    val rotateName = tableName("rotate table", """$escapedInput<<([\w$]+)\[$escapedIndex\]""")

    // The five identified tables fully describe the transform. Avoid matching the complete
    // minified ternary because harmless parenthesis changes vary between otherwise compatible builds.
    if (!body.contains("$memory[$pointer+$index]=$feedbackVariable")) unsupported("write")

    val feedback = when {
        body.contains("$feedbackVariable^=") -> UnmaskFeedback.XOR
        body.contains("255&($feedbackVariable<<1|$feedbackVariable>>7)") -> UnmaskFeedback.ADD_ROTATE_ONE
        else -> unsupported("feedback")
    }

    val permutation = tables[permutationName] ?: unsupported("permutation table")
    val xor = tables[xorName] ?: unsupported("xor mapping")
    val add = tables[addName] ?: unsupported("add mapping")
    val rotate = tables[rotateName] ?: unsupported("rotate mapping")
    val operation = tables[operationName] ?: unsupported("operation mapping")
    if (
        permutation.toSet() != (0 until UNMASK_SIZE).toSet() ||
        xor.any { it !in 0..0xFF } ||
        add.any { it !in 0..0xFF } ||
        rotate.any { it !in 1..7 } ||
        operation.any { it !in 0..2 } ||
        feedbackSeed !in 0..0xFF
    ) {
        unsupported("table values")
    }

    return Unmask(
        permutation = permutation,
        xor = xor,
        add = add,
        rotate = rotate,
        operation = operation,
        rounds = rounds,
        reverse = reverse,
        feedback = feedback,
        feedbackSeed = feedbackSeed,
    )
}

internal const val UNMASK_SIZE = 64
private const val WASM_PREFIX = "\"AGFzbQ"

private val ENTRY_REGEX = Regex("""<script[^>]+src="(?:https?://[^/"]+)?/build/assets/([^"]+\.js)"""")
private val READER_CHUNK_REGEX = Regex(
    """"\./pages/(?:[\w-]+/)*serie-chapter-reader\.tsx":\(\)=>[\w$]+\(\(\)=>import\("\./([^"]+\.js)"\)(?:\.then\([^)]*\))?(?:,__vite__mapDeps\(\[([\d,]*)\]\))?""",
)
private val IMPORT_REGEX = Regex("""from"\./([^"]+\.js)"""")
private val EXPORT_MAP_REGEX = Regex("""\{freeBuffer:"[^}]+\}""")
private val PAIR_REGEX = Regex("""([\w$]+):"(_[\w$]+)"""")
private val MAP_DEPS_REGEX = Regex("""m\.f=\[([^\]]+)\]""")
private val DEP_REGEX = Regex(""""assets/([^"]+)"""")
private val GLUE_EXPORT_REGEX = Regex("""[\w$]+\.(_[\w$]+)=[\w$]+\.([\w$]+)""")
private val CTORS_REGEX = Regex("""=!0,[\w$]+\.([\w$]+)\(\),null==""")

// emscripten_resize_heap, the first entry of the glue's import object.
private val RESIZE_IMPORT_REGEX = Regex(
    """([\w$]+)=\{([\w$]+):[\w$]+=>\{var [\w$]+=[\w$]+\.length;if\(\d+<\([\w$]+>>>=0\)\)return!1""",
)

// name:function(p){for(var a=[..],b=[..],c=[..],d=[..],e=[..],f=0;n>f;f++)for(var
//   g=mem.slice(p,p+64),h=seed,i=start;cond;i++){var j=g[a[i]];h<fold>,mem[p+i]=h
private val UNMASK_REGEX = Regex(
    """([\w$]+):function\(([\w$]+)\)\{for\(var ([\w$]+)=\[([\d,]+)\],([\w$]+)=\[([\d,]+)\],([\w$]+)=\[([\d,]+)\],([\w$]+)=\[([\d,]+)\],([\w$]+)=\[([\d,]+)\],[\w$]+=0;(\d+)>[\w$]+;[\w$]+\+\+\)for\(var ([\w$]+)=[\w$]+\.slice\(([\w$]+),([\w$]+)\+64\),([\w$]+)=(\d+),([\w$]+)=(\d+);([^;]+);([\w$]+)(\+\+|--)\)\{var [\w$]+=([\w$]+)\[([\w$]+)\[([\w$]+)\]\];([^,]*)""",
)

// Newer builds use five tables, multiple rounds and feedback between bytes.
private val ADVANCED_UNMASK_FUNCTION_REGEX = Regex(
    """([\w$]+):function\(([\w$]+)\)\{(for\(var [\s\S]*?\.slice\([\w$]+,[\w$]+\+64\)[\s\S]*?)\}\}""",
)
private val ADVANCED_TABLE_REGEX = Regex("""(?:for\(var |,)([\w$]+)=\[([\d,]+)\]""")
private val ADVANCED_ROUNDS_REGEX = Regex(""",([\w$]+)=0;(\d+)>([\w$]+);([\w$]+)\+\+\)for\(""")
private val WASM_REGEX = Regex(""""(AGFzbQ[A-Za-z0-9+/=]+)"""")
