package com.mivik.kamet.ast

import com.mivik.kamet.BinOp
import com.mivik.kamet.Context
import com.mivik.kamet.IllegalCastException
import com.mivik.kamet.Type
import com.mivik.kamet.Value
import com.mivik.kamet.ValueRef
import com.mivik.kamet.implicitCast
import com.mivik.kamet.reference
import com.mivik.kamet.unreachable
import org.bytedeco.llvm.LLVM.LLVMBuilderRef
import org.bytedeco.llvm.global.LLVM.*

internal class BinOpNode(val lhs: ASTNode, val rhs: ASTNode, val op: BinOp) : ASTNode {
	private fun getOperandType(lhsValue: Value, rhsValue: Value): Type = run {
		val lhsType = lhsValue.type
		val rhsType = rhsValue.type
		if (lhsType !is Type.Primitive || rhsType !is Type.Primitive) TODO()
		else if (lhsType == Type.Primitive.Real.Double || rhsType == Type.Primitive.Real.Double) Type.Primitive.Real.Double
		else if (lhsType == Type.Primitive.Real.Float || rhsType == Type.Primitive.Real.Float) Type.Primitive.Real.Float
		else if (lhsType == Type.Primitive.Boolean && rhsType == Type.Primitive.Boolean) Type.Primitive.Boolean
		else {
			lhsType as Type.Primitive.Integer
			rhsType as Type.Primitive.Integer
			if (lhsType.sizeInBits > rhsType.sizeInBits) lhsType
			else if (lhsType.sizeInBits == rhsType.sizeInBits) {
				if (!lhsType.signed) lhsType
				else rhsType
			} else rhsType
		}
	}

	private fun lift(builder: LLVMBuilderRef, value: Value, type: Type): Value {
		if (value.type == type) return value
		if (type !is Type.Primitive) TODO()
		return Value(
			when (type) {
				is Type.Primitive.Integer -> {
					when (value.type) {
						is Type.Primitive.Integer ->
							if (type.signed) LLVMBuildSExt(builder, value.llvm, type.llvm, "signed_ext")
							else LLVMBuildZExt(builder, value.llvm, type.llvm, "unsigned_ext")
						is Type.Primitive.Real ->
							if (type.signed) LLVMBuildFPToSI(builder, value.llvm, type.llvm, "real_to_signed")
							else LLVMBuildFPToUI(builder, value.llvm, type.llvm, "real_to_unsigned")
						else -> throw IllegalCastException(value.type, type)
					}
				}
				is Type.Primitive.Real -> {
					when (value.type) {
						is Type.Primitive.Integer ->
							if (value.type.signed)
								LLVMBuildSIToFP(builder, value.llvm, type.llvm, "signed_to_real")
							else
								LLVMBuildUIToFP(builder, value.llvm, type.llvm, "unsigned_to_real")
						is Type.Primitive.Real ->
							LLVMBuildFPExt(builder, value.llvm, type.llvm, "real_ext")
						else -> throw IllegalCastException(value.type, type)
					}
				}
				else -> throw IllegalCastException(value.type, type)
			}, type
		)
	}

	override fun codegen(context: Context): Value =
		when (op) {
			is BinOp.AssignOperators -> {
				val lhs = lhs.codegen(context)
				val rhs = rhs.codegen(context).dereference(context)
				require(lhs is ValueRef && !lhs.isConst) { "Assigning to a non-reference type: ${lhs.type}" }
				lhs.set(context, codegen(context, lhs.dereference(context), rhs, op.originalOp))
				lhs
			}
			BinOp.Assign -> {
				val lhs = lhs.codegen(context)
				val rhs = rhs.codegen(context)
				require(lhs is ValueRef && !lhs.isConst) { "Assigning to a non-reference type: ${lhs.type}" }
				lhs.set(context, rhs.dereference(context).implicitCast(context, lhs.originalType))
				lhs
			}
			BinOp.AccessMember -> {
				require(rhs is ValueNode) { "Expected a member name, got $rhs" }
				val lhs = lhs.codegen(context)
				val type = lhs.type
				// TODO extension
				if (type is Type.Struct) {
					val addr = context.declareVariable("struct_store", lhs)
					val index = type.memberIndex(rhs.name)
					Value(
						LLVMBuildStructGEP(context.builder, addr.llvm, index, "access_member"),
						type.memberType(index).reference(true)
					)
				} else {
					val originalType = (type as Type.Reference).originalType as Type.Struct
					val index = originalType.memberIndex(rhs.name)
					ValueRef(
						LLVMBuildStructGEP(context.builder, lhs.llvm, index, "access_member"),
						originalType.memberType(index),
						type.isConst
					)
				}
			}
			else -> codegen(
				context,
				lhs.codegen(context).dereference(context),
				rhs.codegen(context).dereference(context),
				op
			)
		}

	private fun codegen(context: Context, lhs: Value, rhs: Value, op: BinOp): Value {
		val operandType = getOperandType(lhs, rhs)
		val type =
			if (op.returnBoolean) Type.Primitive.Boolean
			else operandType
		val builder = context.builder
		val lhsValue = lift(builder, lhs, operandType).llvm
		val rhsValue = lift(builder, rhs, operandType).llvm
		if (operandType == Type.Primitive.Boolean) {
			return Value(
				when (op) {
					BinOp.And -> LLVMBuildAnd(builder, lhsValue, rhsValue, "and")
					BinOp.Or -> LLVMBuildOr(builder, lhsValue, rhsValue, "or")
					else ->
						LLVMBuildICmp(
							builder, when (op) {
								BinOp.Equal -> LLVMIntEQ
								BinOp.NotEqual -> LLVMIntNE
								BinOp.Less -> LLVMIntULT
								BinOp.LessOrEqual -> LLVMIntULE
								BinOp.Greater -> LLVMIntUGT
								BinOp.GreaterOrEqual -> LLVMIntUGE
								else -> unreachable()
							}, lhsValue, rhsValue, "boolean_cmp"
						)
				}, Type.Primitive.Boolean
			)
		}
		return if (op.returnBoolean)
			Value(
				when (operandType) {
					is Type.Primitive.Integer -> {
						LLVMBuildICmp(
							builder, when (op) {
								BinOp.Equal -> LLVMIntEQ
								BinOp.NotEqual -> LLVMIntNE
								BinOp.Less ->
									if (operandType.signed) LLVMIntSLT
									else LLVMIntULT
								BinOp.LessOrEqual ->
									if (operandType.signed) LLVMIntSLE
									else LLVMIntULE
								BinOp.Greater ->
									if (operandType.signed) LLVMIntSGT
									else LLVMIntUGT
								BinOp.GreaterOrEqual ->
									if (operandType.signed) LLVMIntSGE
									else LLVMIntUGE
								else -> unreachable()
							}, lhsValue, rhsValue, "integer_cmp"
						)
					}
					is Type.Primitive.Real -> LLVMBuildFCmp(
						builder, when (op) {
							BinOp.Equal -> LLVMRealOEQ
							BinOp.NotEqual -> LLVMRealONE
							BinOp.Less -> LLVMRealOLT
							BinOp.LessOrEqual -> LLVMRealOLE
							BinOp.Greater -> LLVMRealOGT
							BinOp.GreaterOrEqual -> LLVMRealOGE
							else -> unreachable()
						}, lhsValue, rhsValue, "real_cmp"
					)
					else -> unreachable()
				}, Type.Primitive.Boolean
			)
		else Value(
			when (type) {
				is Type.Primitive.Integer ->
					LLVMBuildBinOp(
						builder, when (op) {
							BinOp.Plus -> LLVMAdd
							BinOp.Minus -> LLVMSub
							BinOp.Multiply -> LLVMMul
							BinOp.Divide ->
								if (type.signed) LLVMSDiv
								else LLVMUDiv
							BinOp.Reminder ->
								if (type.signed) LLVMSRem
								else LLVMURem
							BinOp.ShiftLeft -> LLVMShl
							BinOp.ShiftRight ->
								if (type.signed) LLVMAShr
								else LLVMLShr
							BinOp.BitwiseAnd -> LLVMAnd
							BinOp.BitwiseOr -> LLVMOr
							BinOp.Xor -> LLVMXor
							else -> unreachable()
						}, lhsValue, rhsValue, "integer_binop"
					)
				is Type.Primitive.Real ->
					LLVMBuildBinOp(
						builder, when (op) {
							BinOp.Plus -> LLVMFAdd
							BinOp.Minus -> LLVMFSub
							BinOp.Multiply -> LLVMFMul
							BinOp.Divide -> LLVMFDiv
							BinOp.Reminder -> LLVMFRem
							else -> unreachable()
						}, lhsValue, rhsValue, "real_binop"
					)
				else -> unreachable()
			}, type
		)
	}

	override fun toString(): String = "($lhs ${op.symbol} $rhs)"
}