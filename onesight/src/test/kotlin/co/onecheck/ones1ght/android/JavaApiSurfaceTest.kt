package co.onecheck.ones1ght.android

import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.jar.JarFile
import kotlin.metadata.ClassKind
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공개 API 가 Java 에서 "Kotlin 티 없이" 불리는지 — 컴파일된 클래스를 리플렉션으로 훑는다(사양서 §3.0).
 *
 * 공개(public/protected) 멤버가 아래 중 하나라도 어기면 실패한다.
 *  (a) kotlin.coroutines.Continuation 을 받는데(= suspend) 같은 이름의 Callback 판이 없다
 *  (b) 매개변수·반환 타입에 kotlin.jvm.functions.FunctionN(람다 타입)이 나온다
 *  (c) kotlin.Unit 을 반환하거나, 매개변수 타입 인자로 Unit 을 받는다(Callback<Unit> 등 — Java 에서 Unit.INSTANCE 를 다루게 된다)
 *  (d) object 의 멤버가 static 이 아니다(OneS1ght.INSTANCE.foo() 가 아니라 OneS1ght.foo() 여야 한다),
 *      companion 멤버는 바깥 클래스에 static 판이 있어야 한다
 *
 * "공개" 판정은 JVM 수식어가 아니라 Kotlin 메타데이터 기준이다 — internal 클래스·생성자는 JVM 에선
 * public 으로 나오기 때문이다.
 *
 * 허용 목록([ALLOWED]) 에는 반드시 왜 괜찮은지 주석을 단다.
 */
class JavaApiSurfaceTest {

    @Test fun publicApiIsJavaFriendly() {
        val classes = publicClasses()
        assertTrue("공개 클래스를 하나도 못 찾았다 — 스캔 경로가 틀렸다", classes.any { it == OneS1ght::class.java })
        val violations = classes.flatMap { violationsOf(it) }.filterNot { it.key in ALLOWED }
        assertEquals(
            "Java 친화 규칙 위반(사양서 §3.0):\n" + violations.joinToString("\n") { "  ${it.key} — ${it.reason}" },
            emptyList<String>(), violations.map { it.key },
        )
    }

    /** 규칙이 실제로 잡는지 — 일부러 어긴 가짜 클래스를 검사기에 넣어본다. */
    @Test fun guardCatchesViolations() {
        val found = violationsOf(Bad::class.java).map { it.key.substringAfter('#') }.toSet()
        assertTrue(found.toString(), "fetch(Continuation)" in found)            // (a)
        assertTrue(found.toString(), "onEvent(Function1)" in found)             // (b)
        assertTrue(found.toString(), "make()" in found)                          // (c) 반환 Unit
        assertTrue(found.toString(), "take(Callback)" in found)                  // (c) Callback<Unit>
        val objFound = violationsOf(BadObject::class.java).map { it.key.substringAfter('#') }.toSet()
        assertTrue(objFound.toString(), "ping()" in objFound)                   // (d)
        val okFound = violationsOf(Good::class.java)
        assertTrue(okFound.toString(), okFound.isEmpty())
    }

    // --- 검사기 -----------------------------------------------------------------------------

    private data class Violation(val key: String, val reason: String)

    private fun violationsOf(cls: Class<*>): List<Violation> {
        val meta = kotlinMeta(cls)
        val visible = publicMemberSignatures(meta)
        val out = mutableListOf<Violation>()
        // Java 에 보이는 공개 멤버. 메타데이터에 internal/private 로 적힌 것은 빼고,
        // 메타데이터에 없는 것(@JvmOverloads 판·@JvmStatic 판 등)은 넣는다.
        val declared = cls.declaredMethods.filter { m ->
            (Modifier.isPublic(m.modifiers) || Modifier.isProtected(m.modifiers)) &&
                !m.isBridge && '$' !in m.name &&
                (visible == null || jvmSig(m) !in visible.hidden)
        }
        // @JvmSynthetic(Java 에서 안 보임)은 (b)~(d) 대상이 아니다. (a) 는 Java 에 같은 기능이 있는지를 보므로 포함한다.
        val methods = declared.filterNot { it.isSynthetic }
        fun key(m: Method) = "${cls.name}#${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

        // (a)
        for (m in declared) {
            if (m.parameterTypes.lastOrNull()?.name != "kotlin.coroutines.Continuation") continue
            val hasCallbackTwin = methods.any { o ->
                o.name == m.name && Modifier.isPublic(o.modifiers) && o.parameterTypes.any { it == Callback::class.java }
            }
            if (!hasCallbackTwin) out += Violation(key(m), "suspend 인데 같은 이름의 Callback 판이 없다")
        }
        for (m in methods) {
            // (b)
            val types = m.genericParameterTypes.filterNot { rawOf(it)?.name == "kotlin.coroutines.Continuation" } + m.genericReturnType
            if (types.any { mentions(it) { c -> c.name.startsWith("kotlin.jvm.functions.") } }) {
                out += Violation(key(m), "람다 타입(kotlin.jvm.functions.*) 이 드러난다 — fun interface 로")
            }
            // (c)
            if (m.returnType.name == "kotlin.Unit") out += Violation(key(m), "kotlin.Unit 을 반환한다")
            else if (types.dropLast(1).any { mentions(it) { c -> c.name == "kotlin.Unit" } }) {
                out += Violation(key(m), "매개변수 타입에 kotlin.Unit 이 있다 — Callback<Void?> 로")
            }
        }
        // (b) 생성자
        for (c in cls.declaredConstructors) {
            if (!Modifier.isPublic(c.modifiers) && !Modifier.isProtected(c.modifiers)) continue
            if (c.isSynthetic) continue
            if (visible != null && jvmSig(c) !in visible.methods) continue
            if (c.genericParameterTypes.any { mentions(it) { t -> t.name.startsWith("kotlin.jvm.functions.") } }) {
                out += Violation("${cls.name}#<init>(${c.parameterTypes.joinToString(",") { it.simpleName }})", "생성자에 람다 타입")
            }
        }
        // (d)
        when (meta?.kind) {
            ClassKind.OBJECT -> for (m in methods) {
                if (!Modifier.isStatic(m.modifiers) && m.name !in ANY_METHODS) {
                    out += Violation(key(m), "object 멤버인데 static 이 아니다 — @JvmStatic/const/@JvmField")
                }
            }
            ClassKind.COMPANION_OBJECT -> {
                val outer = cls.declaringClass
                for (m in methods) {
                    if (m.name in ANY_METHODS) continue
                    val twin = outer.declaredMethods.any {
                        it.name == m.name && Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(m.parameterTypes)
                    }
                    if (!twin) out += Violation(key(m), "companion 멤버인데 바깥 클래스에 static 판이 없다 — @JvmStatic")
                }
            }
            else -> Unit
        }
        return out
    }

    private class MemberSigs(val methods: Set<String>, val hidden: Set<String>)

    /** 메타데이터에 적힌 멤버 중 public/protected 인 것의 JVM 시그니처(그 외는 hidden). 메타데이터 없으면 null. */
    private fun publicMemberSignatures(meta: KotlinClassMetadata?): MemberSigs? {
        val shown = mutableSetOf<String>()
        val hidden = mutableSetOf<String>()
        fun add(sig: JvmMethodSignature?, vis: Visibility) {
            if (sig == null) return
            (if (vis == Visibility.PUBLIC || vis == Visibility.PROTECTED) shown else hidden) += sig.toString()
        }
        when (meta) {
            is KotlinClassMetadata.Class -> {
                val k = meta.kmClass
                k.functions.forEach { add(it.signature, it.visibility) }
                k.constructors.forEach { add(it.signature, it.visibility) }
                k.properties.forEach { p ->
                    add(p.getterSignature, p.getter.visibility)
                    p.setter?.let { s -> add(p.setterSignature, s.visibility) }
                }
            }
            is KotlinClassMetadata.FileFacade -> {
                val k = meta.kmPackage
                k.functions.forEach { add(it.signature, it.visibility) }
                k.properties.forEach { p ->
                    add(p.getterSignature, p.getter.visibility)
                    p.setter?.let { s -> add(p.setterSignature, s.visibility) }
                }
            }
            is KotlinClassMetadata.MultiFileClassPart -> {
                val k = meta.kmPackage
                k.functions.forEach { add(it.signature, it.visibility) }
            }
            null -> return null
            else -> Unit
        }
        return MemberSigs(shown, hidden)
    }

    private val KotlinClassMetadata?.kind: ClassKind?
        get() = (this as? KotlinClassMetadata.Class)?.kmClass?.kind

    private fun kotlinMeta(cls: Class<*>): KotlinClassMetadata? =
        cls.getAnnotation(Metadata::class.java)?.let { KotlinClassMetadata.readLenient(it) }

    /** 스캔 대상: 라이브러리 main 클래스 중 Kotlin 기준으로 밖에서 보이는 것(바깥 클래스까지 전부 공개). */
    private fun publicClasses(): List<Class<*>> {
        val root = File(OneS1ght::class.java.protectionDomain.codeSource.location.toURI())
        val names = if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.toList()
        } else {
            JarFile(root).use { jar -> jar.entries().toList().map { it.name }.filter { it.endsWith(".class") } }
        }
        val loaded = mutableListOf<Class<*>>()
        val unloadable = mutableListOf<String>()
        for (n in names.filter { it.startsWith(PACKAGE_PATH) }) {
            val fqcn = n.removeSuffix(".class").replace('/', '.')
            try {
                loaded += Class.forName(fqcn, false, javaClass.classLoader)
            } catch (e: LinkageError) {
                // 엔진(compileOnly 스텁)을 구현하는 내부 익명 클래스 등 — 테스트 런타임에 엔진이 없어 못 올린다.
                // 바깥(최상위) 클래스가 공개면 검사를 못 한 채 넘어가는 셈이라 실패시킨다.
                unloadable += fqcn
            }
        }
        val blind = unloadable.filter { n ->
            val top = n.substringBefore('$')
            top == n || loaded.firstOrNull { it.name == top }?.let { isVisible(it) } != false
        }
        assertTrue("공개 클래스인데 테스트 런타임에서 못 올려 검사 못 함: $blind", blind.isEmpty())
        return loaded.filter { isVisible(it) }.sortedBy { it.name }
    }

    private fun isVisible(cls: Class<*>): Boolean {
        if (cls.isSynthetic || cls.isAnonymousClass || cls.isLocalClass) return false
        if (!Modifier.isPublic(cls.modifiers) && !Modifier.isProtected(cls.modifiers)) return false
        if (cls.simpleName in GENERATED) return false
        when (val meta = kotlinMeta(cls)) {
            is KotlinClassMetadata.Class ->
                if (meta.kmClass.visibility != Visibility.PUBLIC && meta.kmClass.visibility != Visibility.PROTECTED) return false
            is KotlinClassMetadata.FileFacade, is KotlinClassMetadata.MultiFileClassFacade,
            is KotlinClassMetadata.MultiFileClassPart -> Unit
            is KotlinClassMetadata.SyntheticClass -> return false
            null -> Unit // Java 로 쓴 공개 클래스
            else -> return false
        }
        return cls.declaringClass?.let { isVisible(it) } ?: true
    }

    private fun jvmSig(m: Method) = m.name + "(" + m.parameterTypes.joinToString("") { desc(it) } + ")" + desc(m.returnType)
    private fun jvmSig(c: java.lang.reflect.Constructor<*>) = "<init>(" + c.parameterTypes.joinToString("") { desc(it) } + ")V"

    private fun desc(c: Class<*>): String = when {
        c.isArray -> "[" + desc(c.componentType)
        c == Void.TYPE -> "V"; c == java.lang.Boolean.TYPE -> "Z"; c == java.lang.Byte.TYPE -> "B"
        c == Character.TYPE -> "C"; c == java.lang.Short.TYPE -> "S"; c == Integer.TYPE -> "I"
        c == java.lang.Long.TYPE -> "J"; c == java.lang.Float.TYPE -> "F"; c == java.lang.Double.TYPE -> "D"
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    private fun rawOf(t: Type): Class<*>? = when (t) {
        is Class<*> -> t
        is ParameterizedType -> t.rawType as? Class<*>
        else -> null
    }

    private fun mentions(t: Type, pred: (Class<*>) -> Boolean): Boolean = when (t) {
        is Class<*> -> pred(t) || (t.isArray && mentions(t.componentType, pred))
        is ParameterizedType -> mentions(t.rawType, pred) || t.actualTypeArguments.any { mentions(it, pred) }
        is WildcardType -> t.upperBounds.any { mentions(it, pred) } || t.lowerBounds.any { mentions(it, pred) }
        is GenericArrayType -> mentions(t.genericComponentType, pred)
        else -> false
    }

    // --- 검사기 자체 테스트용 가짜 API --------------------------------------------------------

    @Suppress("unused", "UNUSED_PARAMETER")
    class Bad {
        suspend fun fetch(): String = ""
        fun onEvent(f: (String) -> Unit) {}
        fun take(cb: Callback<Unit>) {}
        fun make(): Unit? = null
    }

    @Suppress("unused")
    object BadObject {
        fun ping() {}
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class Good {
        suspend fun fetch(): String = ""
        fun fetch(cb: Callback<String>) {}
        fun done() {}
        fun take(cb: Callback<Void?>) {}
        internal fun hidden(f: () -> Unit) {}
    }

    private companion object {
        const val PACKAGE_PATH = "co/onecheck/ones1ght/android/"
        val ANY_METHODS = setOf("equals", "hashCode", "toString")
        /** 빌드 도구가 만드는 클래스 — 고객 API 가 아니다. */
        val GENERATED = setOf("BuildConfig", "R")

        /**
         * 허용 목록: "클래스#메서드(매개변수 단순이름들)" → 왜 괜찮은지.
         * 규칙을 피해 가는 통로가 아니다 — 비워 두는 게 원칙이고, 넣을 땐 반드시 이유를 쓴다.
         */
        val ALLOWED: Map<String, String> = mapOf()
    }
}

