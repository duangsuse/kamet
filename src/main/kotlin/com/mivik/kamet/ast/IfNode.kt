package com.mivik.kamet.ast

import com.mivik.kamet.Context
import com.mivik.kamet.Value
import org.bytedeco.llvm.global.LLVM

internal class IfNode(val condition: ASTNode, val thenBlock: ASTNode, val elseBlock: ASTNode? = null) : ASTNode {
	override fun codegen(context: Context): Value {
		val builder = context.builder
		val conditionValue = condition.codegen(context)
		val function = context.llvmFunction
		val llvmThenBlock = LLVM.LLVMAppendBasicBlock(function, "then")
		val llvmElseBlock = LLVM.LLVMAppendBasicBlock(function, "else")
		if (elseBlock == null) {
			LLVM.LLVMBuildCondBr(builder, conditionValue.llvm, llvmThenBlock, llvmElseBlock)
			context.setBlock(llvmThenBlock)
			LLVM.LLVMBuildBr(builder, llvmElseBlock)
			context.setBlock(llvmElseBlock)
			return Value.Nothing
		} else {
			val llvmFinalBlock = LLVM.LLVMAppendBasicBlock(function, "final")
			LLVM.LLVMBuildCondBr(builder, conditionValue.llvm, llvmThenBlock, llvmElseBlock)
			context.setBlock(llvmThenBlock)
			val thenRet = thenBlock.codegen(context)
			context.setBlock(llvmElseBlock)
			val elseRet = elseBlock.codegen(context)
			return if (thenRet.type == elseRet.type) {
				val variable = context.declareVariable("if_result", thenRet.type.undefined())
				context.setBlock(llvmThenBlock)
				variable.set(context, thenRet)
				LLVM.LLVMBuildBr(builder, llvmFinalBlock)
				context.setBlock(llvmElseBlock)
				variable.set(context, elseRet)
				LLVM.LLVMBuildBr(builder, llvmFinalBlock)
				context.setBlock(llvmFinalBlock)
				variable.get(context)
			} else {
				context.setBlock(llvmThenBlock)
				LLVM.LLVMBuildBr(builder, llvmFinalBlock)
				context.setBlock(llvmElseBlock)
				LLVM.LLVMBuildBr(builder, llvmFinalBlock)
				context.setBlock(llvmFinalBlock)
				Value.Nothing
			}
		}
	}
}