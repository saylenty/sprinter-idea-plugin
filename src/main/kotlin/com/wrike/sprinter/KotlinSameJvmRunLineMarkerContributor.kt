package com.wrike.sprinter

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.wrike.sprinter.frameworks.testFrameworkForRunningInSharedJVMExtensionPoint
import java.lang.reflect.Method

class KotlinSameJvmRunLineMarkerContributor : RunLineMarkerContributor() {
    private val delegate: Any? = createDelegate()
    private val delegateInfoMethod: Method? = findDelegateMethod("getInfo")
    private val delegateSlowInfoMethod: Method? = findDelegateMethod("getSlowInfo")

    private val ktFileClass: Class<*>? = loadClass("org.jetbrains.kotlin.psi.KtFile")
    private val junitProvider: Any? = loadJunitProvider()
    private val javaTestEntityMethod: Method? = findJavaTestEntityMethod()
    private val javaTestEntityClass: Class<*>? = loadClass("org.jetbrains.kotlin.idea.extensions.KotlinTestFrameworkProvider\$JavaTestEntity")
    private val javaTestEntityGetTestMethod: Method? = javaTestEntityClass?.getMethod("getTestMethod")
    private val javaTestEntityGetTestClass: Method? = javaTestEntityClass?.getMethod("getTestClass")

    override fun getInfo(element: PsiElement): Info? = delegateInfo(delegateInfoMethod, element)

    override fun getSlowInfo(element: PsiElement): Info? = delegateInfo(delegateSlowInfoMethod, element)

    private fun delegateInfo(method: Method?, element: PsiElement): Info? {
        if (delegate == null || method == null) return null
        val delegateResult = try {
            method.invoke(delegate, element)
        } catch (_: Throwable) {
            return null
        }
        if (delegateResult == null) return null
        return calculateInfoIfTestFrameworkIsFound(element)
    }

    private fun calculateInfoIfTestFrameworkIsFound(element: PsiElement): Info? {
        if (!isKotlinFile(element) || javaTestEntityMethod == null || junitProvider == null) {
            return null
        }

        val testEntity = try {
            javaTestEntityMethod.invoke(junitProvider, element, true)
        } catch (_: Throwable) {
            null
        } ?: return null

        val testMethod = javaTestEntityGetTestMethod?.let { invokePsiMethod(it, testEntity) }
        val testClass = javaTestEntityGetTestClass?.let { invokePsiClass(it, testEntity) }

        val canRunTestsForElement = when {
            testMethod != null -> testFrameworkForRunningInSharedJVMExtensionPoint.extensionList.any { it.canRunTestsFor(testMethod) }
            testClass != null -> testFrameworkForRunningInSharedJVMExtensionPoint.extensionList.any { it.canRunTestsFor(testClass) }
            else -> false
        }

        return if (canRunTestsForElement) {
            Info(null, null, ActionManager.getInstance().getAction("RunTestsInExistingJvm"))
        } else {
            null
        }
    }

    private fun invokePsiMethod(method: Method, target: Any): PsiMethod? =
        (runCatching { method.invoke(target) }.getOrNull() as? PsiMethod)

    private fun invokePsiClass(method: Method, target: Any): PsiClass? =
        (runCatching { method.invoke(target) }.getOrNull() as? PsiClass)

    private fun isKotlinFile(element: PsiElement): Boolean =
        ktFileClass?.isInstance(element.containingFile) == true

    private fun createDelegate(): Any? =
        runCatching {
            loadClass("org.jetbrains.kotlin.idea.highlighter.KotlinTestRunLineMarkerContributor")
                ?.getDeclaredConstructor()
                ?.newInstance()
        }.getOrNull()

    private fun findDelegateMethod(name: String): Method? =
        delegate?.javaClass?.methods?.firstOrNull { method ->
            method.name == name &&
                method.parameterCount == 1 &&
                PsiElement::class.java.isAssignableFrom(method.parameterTypes[0])
        }

    private fun loadJunitProvider(): Any? =
        runCatching {
            loadClass("org.jetbrains.kotlin.idea.junit.JunitKotlinTestFrameworkProvider")
                ?.getMethod("getInstance")
                ?.invoke(null)
        }.getOrNull()

    private fun findJavaTestEntityMethod(): Method? {
        val providerClass = junitProvider?.javaClass ?: return null
        val direct = runCatching {
            providerClass.methods.firstOrNull { method ->
                method.name == "getJavaTestEntity" &&
                    method.parameterCount == 2 &&
                    PsiElement::class.java.isAssignableFrom(method.parameterTypes[0])
            }
        }.getOrNull()

        if (direct != null) {
            return direct
        }

        return runCatching {
            loadClass("org.jetbrains.kotlin.idea.extensions.KotlinTestFrameworkProvider")
                ?.getMethod("getJavaTestEntity", PsiElement::class.java, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    private fun loadClass(fqn: String): Class<*>? =
        runCatching { Class.forName(fqn) }.getOrNull()
}
