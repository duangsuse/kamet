package com.mivik.kamet

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMExecutionEngineRef
import org.bytedeco.llvm.LLVM.LLVMModuleRef
import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.*

class JITEngine(module: LLVMModuleRef) {
	companion object {
		init {
			LLVMLinkInMCJIT()
			LLVMInitializeNativeAsmPrinter()
			LLVMInitializeNativeAsmParser()
			LLVMInitializeNativeTarget()
		}
	}

	constructor(context: Context) : this(context.module)

	private val engine: LLVMExecutionEngineRef = LLVMExecutionEngineRef()

	init {
		val error = BytePointer(null as Pointer?)
		if (LLVMCreateJITCompilerForModule(engine, module, 2, error) != 0)
			error("Failed to create JITEngine for module: ${error.settle()}")
	}

	fun findFunction(name: String): LLVMValueRef =
		LLVMValueRef().also { LLVMFindFunction(engine, BytePointer(name), it) }

	@Suppress("NOTHING_TO_INLINE")
	inline fun run(functionName: String, argument: GenericValue): GenericValue =
		run(findFunction(functionName), argument)

	fun run(function: LLVMValueRef, argument: GenericValue): GenericValue =
		GenericValue(LLVMRunFunction(engine, function, 1, argument.llvm))

	@Suppress("NOTHING_TO_INLINE")
	inline fun run(functionName: String): GenericValue = run(findFunction(functionName))

	fun run(function: LLVMValueRef): GenericValue =
		GenericValue(LLVMRunFunction(engine, function, 0, PointerPointer<Nothing>(null as Pointer?)))

	@Suppress("NOTHING_TO_INLINE")
	inline fun runMain() = run("main")

	fun dispose() {
		LLVMDisposeExecutionEngine(engine)
	}
}