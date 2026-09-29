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
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공개 API 가 Java 에서 "Kotlin 티 없이" 불리는지 — 컴파일된 클래스를 리플렉션으로 훑는다(사양서 §3.0).
 *
 * 검사 대상 클래스는 Kotlin 기준 공개 클래스(바깥 클래스까지 공개)와 파일 파사드(XxxKt)다. 그 안의 멤버는
 * **Java 가 보는 것** 기준이다 — JVM public/protected 이고 synthetic 이 아니며 이름이 망글링('$')되지 않은 것.
 * Kotlin internal 생성자·최상위 internal 함수는 JVM 에선 public 이라 여기 걸린다(private 생성자 + @JvmSynthetic 팩토리로 숨긴다).
 *
 *  (a) Kotlin 공개 suspend 오버로드마다, 같은 이름·같은 매개변수 + 끝에 Callback 인 공개 메서드가 있어야 한다
 *  (b) 매개변수·반환·생성자 타입에 kotlin.jvm.functions.FunctionN(람다 타입)이 나온다
 *  (c) kotlin.Unit 을 반환하거나, 매개변수 타입 인자로 Unit 을 받는다(Callback<Unit> 등 — Java 에서 Unit.INSTANCE 를 다루게 된다)
 *  (d) object 의 멤버가 static 이 아니다(OneS1ght.INSTANCE.foo() 가 아니라 OneS1ght.foo() 여야 한다),
 *      companion 멤버는 바깥 클래스에 static 판이 있어야 한다
 *  (e) 시그니처에 api 가 아닌 의존의 타입이 나온다([API_TYPE_PREFIXES])
 *
 * internal 클래스 자체는 대상이 아니다 — Kotlin internal 클래스는 고객 API 가 아니고, 이걸 막으려면 내부 전부를
 * 다시 짜야 한다(JVM 에서 보이는 건 맞다).
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

    /**
     * (e) 공개 시그니처(멤버 타입·상위 타입)에 나오는 외부 타입은 고객 컴파일 클래스패스에 있어야 한다 —
     * 즉 build.gradle.kts 에서 `api` 로 싣는 것(androidx.activity 와 그 전이 api)이거나 JDK·Android·Kotlin 표준이어야 한다.
     * kotlinx.coroutines·okhttp·kotlinx.serialization 은 implementation 이라 여기 나오면 고객이 컴파일을 못 한다.
     */
    @Test fun publicSignaturesOnlyUseApiDependencies() {
        val leaks = sortedSetOf<String>()
        for (cls in publicClasses()) leaks += dependencyLeaksOf(cls)
        assertEquals("공개 시그니처에 api 가 아닌 의존의 타입이 나온다:\n" + leaks.joinToString("\n"), emptySet<String>(), leaks)
    }

    /** 규칙이 실제로 잡는지 — 일부러 어긴 가짜 클래스를 검사기에 넣어본다. */
    @Test fun guardCatchesViolations() {
        val found = violationsOf(Bad::class.java).map { it.key.substringAfter('#') }.toSet()
        assertTrue(found.toString(), "fetch(Continuation)" in found)            // (a) Callback 판 없음
        assertTrue(found.toString(), "setFloorMap(String,String,Continuation)" in found) // (a) 매개변수가 맞는 Callback 판 없음
        assertTrue(found.toString(), "setFloorMap(String,Continuation)" !in found)       // (a) 1개짜리는 짝이 있다
        assertTrue(found.toString(), "<init>(Function0)" in found)              // (b) internal 생성자도 Java 엔 보인다
        assertTrue(found.toString(), "onEvent(Function1)" in found)             // (b)
        assertTrue(found.toString(), "make()" in found)                          // (c) 반환 Unit
        assertTrue(found.toString(), "take(Callback)" in found)                  // (c) Callback<Unit>
        val objFound = violationsOf(BadObject::class.java).map { it.key.substringAfter('#') }.toSet()
        assertTrue(objFound.toString(), "ping()" in objFound)                   // (d)
        val okFound = violationsOf(Good::class.java)
        assertTrue(okFound.toString(), okFound.isEmpty())
        // (e) internal 이어도 JVM 에서 public 인 생성자·멤버의 타입, 그리고 api 가 아닌 androidx 패키지를 잡는다
        val leaks = dependencyLeaksOf(Bad::class.java).map { it.substringAfter(" → ") }.toSet()
        assertTrue(leaks.toString(), "kotlinx.coroutines.CoroutineScope" in leaks)
        assertTrue(leaks.toString(), "androidx.lifecycle.LifecycleOwner" in leaks)
        assertTrue(dependencyLeaksOf(Good::class.java).toString(), dependencyLeaksOf(Good::class.java).isEmpty())
    }

    // --- 검사기 -----------------------------------------------------------------------------

    private data class Violation(val key: String, val reason: String)

    private fun violationsOf(cls: Class<*>): List<Violation> {
        val meta = kotlinMeta(cls)
        val out = mutableListOf<Violation>()
        // (a) 대상: Kotlin 에서 공개인 suspend(메타데이터 기준) — @JvmSynthetic 로 Java 에서 숨긴 것도 포함한다
        //     (Java 에 같은 기능이 있어야 하므로). 메타데이터가 없으면(Java 클래스) Java 에 보이는 것.
        val kotlinPublic = kotlinPublicFunctionSignatures(meta)
        val suspends = cls.declaredMethods.filter { m ->
            (Modifier.isPublic(m.modifiers) || Modifier.isProtected(m.modifiers)) && !m.isBridge && '$' !in m.name &&
                m.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation" &&
                (if (kotlinPublic != null) jvmSig(m) in kotlinPublic else !m.isSynthetic)
        }
        // (b)~(d) 대상: Java 에 보이는 것 전부(Kotlin internal 생성자처럼 JVM 에선 public 인 것 포함).
        val methods = javaVisibleMethods(cls)
        fun key(m: Method) = "${cls.name}#${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

        // (a) 오버로드마다: suspend 매개변수(Continuation 뺀 것) + 끝에 Callback 인 같은 이름의 공개 메서드가 있어야 한다.
        for (m in suspends) {
            val want = m.parameterTypes.dropLast(1) + Callback::class.java
            val hasCallbackTwin = methods.any { o ->
                o.name == m.name && Modifier.isPublic(o.modifiers) && o.parameterTypes.toList() == want
            }
            if (!hasCallbackTwin) {
                out += Violation(key(m), "suspend 인데 같은 매개변수 + Callback 인 ${m.name}(${want.joinToString(",") { it.simpleName }}) 이 없다")
            }
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
        for (c in javaVisibleConstructors(cls)) {
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

    /** 메타데이터상 public/protected 함수의 JVM 시그니처("name(desc)ret"). 메타데이터가 없으면 null. */
    private fun kotlinPublicFunctionSignatures(meta: KotlinClassMetadata?): Set<String>? {
        val fns = when (meta) {
            is KotlinClassMetadata.Class -> meta.kmClass.functions
            is KotlinClassMetadata.FileFacade -> meta.kmPackage.functions
            is KotlinClassMetadata.MultiFileClassPart -> meta.kmPackage.functions
            null -> return null
            else -> return emptySet()
        }
        return fns.filter { it.visibility == Visibility.PUBLIC || it.visibility == Visibility.PROTECTED }
            .mapNotNull { it.signature?.toString() }.toSet()
    }

    private fun jvmSig(m: Method) = m.name + "(" + m.parameterTypes.joinToString("") { desc(it) } + ")" + desc(m.returnType)

    private fun desc(c: Class<*>): String = when {
        c.isArray -> "[" + desc(c.componentType)
        c == Void.TYPE -> "V"; c == java.lang.Boolean.TYPE -> "Z"; c == java.lang.Byte.TYPE -> "B"
        c == Character.TYPE -> "C"; c == java.lang.Short.TYPE -> "S"; c == Integer.TYPE -> "I"
        c == java.lang.Long.TYPE -> "J"; c == java.lang.Float.TYPE -> "F"; c == java.lang.Double.TYPE -> "D"
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    /** Java 에서 보이는 메서드 — JVM public/protected 이고 synthetic·bridge 가 아니며 이름이 망글링('$')되지 않은 것. */
    private fun javaVisibleMethods(cls: Class<*>): List<Method> = cls.declaredMethods.filter { m ->
        (Modifier.isPublic(m.modifiers) || Modifier.isProtected(m.modifiers)) && !m.isSynthetic && !m.isBridge && '$' !in m.name
    }

    private fun javaVisibleConstructors(cls: Class<*>) = cls.declaredConstructors.filter { c ->
        (Modifier.isPublic(c.modifiers) || Modifier.isProtected(c.modifiers)) && !c.isSynthetic
    }

    /** (e) Java 에 보이는 시그니처(상위 타입·메서드·생성자·필드)에서 api 가 아닌 의존의 타입을 모은다. */
    private fun dependencyLeaksOf(cls: Class<*>): List<String> {
        val types = mutableListOf<Type>()
        cls.genericSuperclass?.let { types += it }
        types += cls.genericInterfaces
        javaVisibleMethods(cls).forEach { m -> types += m.genericParameterTypes; types += m.genericReturnType }
        javaVisibleConstructors(cls).forEach { c -> types += c.genericParameterTypes }
        cls.declaredFields.filter { (Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers)) && !it.isSynthetic }
            .forEach { types += it.genericType }
        val leaks = sortedSetOf<String>()
        for (t in types) {
            mentions(t) { c ->
                if (!c.isPrimitive && !c.isArray && API_TYPE_PREFIXES.none { c.name.startsWith(it) }) leaks += "${cls.name} → ${c.name}"
                false
            }
        }
        return leaks.toList()
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
    class Bad internal constructor(f: () -> Unit) {
        constructor() : this({})
        internal constructor(scope: kotlinx.coroutines.CoroutineScope, owner: androidx.lifecycle.LifecycleOwner) : this()
        suspend fun fetch(): String = ""
        suspend fun setFloorMap(floor: String?) {}
        suspend fun setFloorMap(floor: String?, buildingId: String?) {}
        fun setFloorMap(floor: String?, cb: Callback<Void?>) {}
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
         * 공개 시그니처에 나와도 되는 타입 — JDK·Android 프레임워크·Kotlin 표준(api: kotlin-stdlib)·우리 SDK, 그리고
         * build.gradle.kts 에서 `api` 로 싣는 의존의 패키지만. androidx 는 통째로 허용하지 않는다 — 지금 api 는
         * androidx.activity 하나다(ComponentActivity 의 상위 타입이 끌고 오는 core·lifecycle 은 activity 의 api 로
         * 고객에게 따라가지만, 우리 시그니처에 직접 쓰면 여기서 막고 api 로 올릴지 먼저 정한다).
         */
        val API_TYPE_PREFIXES = listOf(
            "java.", "javax.", "android.", "kotlin.", "org.jetbrains.annotations.",
            "androidx.activity.",
            // UwbPositioningProvider 의 상태 흐름(StateFlow) — kotlinx-coroutines-core 를 api 로 싣는다(0.0.5~).
            // flow 패키지만 연다 — CoroutineScope·Dispatcher 같은 실행 도구가 공개 시그니처에 새면 여전히 잡는다.
            "kotlinx.coroutines.flow.",
            "co.onecheck.ones1ght.android.",
        )

        /**
         * 허용 목록: "클래스#메서드(매개변수 단순이름들)" → 왜 괜찮은지.
         * 규칙을 피해 가는 통로가 아니다 — 비워 두는 게 원칙이고, 넣을 땐 반드시 이유를 쓴다.
         */
        val ALLOWED: Map<String, String> = mapOf()
    }
}

