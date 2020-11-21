package com.mdrobnak.lalrpop.psi.ext

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.mdrobnak.lalrpop.psi.*
import com.mdrobnak.lalrpop.psi.impl.LpNonterminalImpl

val LpNonterminalName.nonterminalParent: LpNonterminal
    get() = this.parent as LpNonterminal

abstract class LpNonterminalNameMixin(node: ASTNode) : ASTWrapperPsiElement(node), LpNonterminalName {
    override fun getNameIdentifier(): PsiElement {
        return node.findChildByType(LpElementTypes.ID)!!.psi
    }

    override fun getName(): String {
        return nameIdentifier.text
    }

    override fun setName(name: String): PsiElement {
        val newNode = LpElementFactory(project).createIdentifier(name)
        nameIdentifier.replace(newNode)
        return this
    }
}
