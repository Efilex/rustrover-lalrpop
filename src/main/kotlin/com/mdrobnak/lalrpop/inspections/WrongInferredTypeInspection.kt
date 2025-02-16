package com.mdrobnak.lalrpop.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.mdrobnak.lalrpop.injectors.findModuleDefinition
import com.mdrobnak.lalrpop.psi.LpMacroArguments
import com.mdrobnak.lalrpop.psi.LpNonterminal
import com.mdrobnak.lalrpop.psi.LpVisitor
import com.mdrobnak.lalrpop.psi.ext.importCode
import com.mdrobnak.lalrpop.psi.ext.rustGenericUnitStructs
import com.mdrobnak.lalrpop.psi.getContextAndResolveType
import org.rust.lang.RsLanguage
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.macros.setContext
import org.rust.lang.core.psi.RsTypeAlias
import org.rust.lang.core.psi.ext.childrenOfType
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.resolve.ImplLookup

/**
 * Reports 2 kinds of problems on a nonterminal
 * 1. The inferred type is different from the type declared explicitly on the nonterminal
 * 2. There are 2 different alternatives, without action code, where the inferred types are different
 */
object WrongInferredTypeInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor = object : LpVisitor() {
        override fun visitNonterminal(nonterminal: LpNonterminal) {
            val explicitType = nonterminal.typeRef?.getContextAndResolveType(LpMacroArguments.identity(nonterminal.nonterminalName.nonterminalParams))

            // LALRPOP will automatically supply action code of (), so we don't need to worry about inferring the wrong type.
            // https://github.com/lalrpop/lalrpop/blob/8a96e9646b3d00c2226349efed832c4c25631c53/lalrpop/src/normalize/lower/mod.rs#L351-L354
            if (explicitType == "()") return

            var seenType = explicitType

            val unitStructsGenerics by lazy { nonterminal.rustGenericUnitStructs() }

            nonterminal.alternatives.alternativeList.filter { it.action == null }.forEach { alternative ->
                val alternativeType = alternative.getContextAndResolveType(LpMacroArguments.identity(nonterminal.nonterminalName.nonterminalParams))

                if (seenType == null) {
                    seenType = alternativeType
                    return@forEach
                }

                if (!sameTypes(
                        nonterminal.project,
                        nonterminal.containingFile,
                        nonterminal.containingFile.importCode,
                        unitStructsGenerics,
                        seenType!!,
                        alternativeType
                    )
                ) {
                    holder.registerProblem(
                        alternative,
                        "Resolved type of alternative is `$alternativeType`, while expected type is `$seenType`"
                    )
                }
            }
        }
    }

    fun sameTypes(
        project: Project,
        lalrpopFile: PsiFile,
        importCode: String,
        unitStructsGenerics: String,
        type1: String,
        type2: String
    ): Boolean {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText(
                RsLanguage,
                """
                    mod __intellij_lalrpop {
                        $importCode
                        $unitStructsGenerics
                        type T1 = $type1;
                        type T2 = $type2;
                    }
                    """.trimIndent()
            )
        val aliases = file.descendantsOfType<RsTypeAlias>().toList()

        if (aliases.size != 2) return false

        val (first, second) = aliases

        val moduleDefinition = findModuleDefinition(project, lalrpopFile) ?: return false

        for (child in file.childrenOfType<RsExpandedElement>()) {
            child.setContext(moduleDefinition)
        }

        val ctx = ImplLookup.relativeTo(first).ctx
        val ty1 = ctx.fullyResolve(first.declaredType)
        val ty2 = ctx.fullyResolve(second.declaredType)

        return ctx.canCombineTypes(ty1, ty2)
    }
}
