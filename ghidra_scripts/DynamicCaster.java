/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2019-2020 Andrew J. Strelsky
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */
// Finds all calls to __dynamic_cast, determines the source and destination data types from the
// __class_type_info parameters and then generated a function signature override. This is extremely
// useful as it assists the decompiler's type propogation algorithm which cannot handle virtual classes.
//@category CppClassAnalyzer
//@author Andrew J. Strelsky
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.gcc.TypeInfoUtils;
import ghidra.app.cmd.data.rtti.gcc.factory.TypeInfoFactory;
import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.app.plugin.core.analysis.ConstantPropagationAnalyzer;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionSignature;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.DataTypeSymbol;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.util.SymbolicPropogator;
import ghidra.program.util.SymbolicPropogator.Value;
import ghidra.util.classfinder.ClassSearcher;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

import cppclassanalyzer.script.CppClassAnalyzerGhidraScript;

public class DynamicCaster extends CppClassAnalyzerGhidraScript {

	private static final String UNSUPPORTED_MESSAGE =
		"Currently only processors passing parameters via registers are supported.";
	private static final String FORMAL_SIGNATURE =
		"void * __dynamic_cast(void * src_ptr, __class_type_info * src_type, __class_type_info * dst_type, ptrdiff_t src2dst)";
	private static final String DYNAMIC_CAST = "__dynamic_cast";
	private static final String TMP_NAME = "tmpname";
	private static final String NAME_ROOT = "prt";
	private static final String AUTO_CAT = "/auto_proto";

	private Register srcReg;
	private Register destReg;
	private SymbolTable table;
	private DataTypeManager dtm;
	private FunctionSignature sig;
	private ConstantPropagationAnalyzer analyzer;

	@Override
	public void run() throws Exception {
		table = currentProgram.getSymbolTable();
		dtm = currentProgram.getDataTypeManager();
		analyzer = getConstantAnalyzer(currentProgram);
		final Function dynamicCast = getDynamicCast();
		if (dynamicCast == null) {
			return;
		}
		if (!dynamicCast.getPrototypeString(true, false).equals(FORMAL_SIGNATURE)) {
			printerr("The function at "+dynamicCast.getEntryPoint().toString()
					 +" doesnt match the cxxabi defined functions signature:\n"
					 +FORMAL_SIGNATURE);
			return;
		}
		sig = dynamicCast.getSignature();
		final Parameter[] parameters = dynamicCast.getParameters();
		if (parameters.length != 4) {
			printerr("Unexpected number of __dynamic_cast parameters");
			return;
		}
		if (!parameters[2].isRegisterVariable()) {
			printerr(UNSUPPORTED_MESSAGE);
			return;
		}
		srcReg = parameters[1].getRegister();
		destReg = parameters[2].getRegister();
		final ReferenceManager manager = currentProgram.getReferenceManager();
		final List<Address> addresses =
			Arrays.stream(getReferencesTo(dynamicCast.getEntryPoint()))
				  .filter(DynamicCaster::isCall)
				  .map(Reference::getFromAddress)
				  .filter(Predicate.not(manager::hasReferencesTo))
				  .collect(Collectors.toList());
		monitor.initialize(addresses.size());
		monitor.setMessage("Analyzing __dynamic_cast calls");
		for (Address address : addresses) {
			monitor.checkCanceled();
			doDynamicCast(address);
			monitor.incrementProgress(1);
		}
	}

	private Function getDynamicCast() {
		List<Function> functions = getGlobalFunctions(DYNAMIC_CAST);
		if (functions.size() > 1) {
			printerr("More than one __dynamic_cast function found.");
			return null;
		}
		if (functions.isEmpty()) {
			printerr("__dynamic_cast function not found");
			return null;
		}
		return functions.get(0);
	}

	private static boolean isCall(Reference r) {
		final RefType type = r.getReferenceType();
		if (type.isCall()) {
			return !(type.isComputed() || type.isIndirect());
		}
		return false;
	}

	private Address getDelayAddress(Address address) {
		Instruction inst = getInstructionAt(address);
		int depth = inst.getDelaySlotDepth();
		if (depth > 0) {
			while (depth >= 0) {
				inst = inst.getNext();
				depth--;
			}
		}
		return inst.getAddress();
	}

	private ClassTypeInfo getType(SymbolicPropogator prop, Address address, Register reg) {
		Value value = prop.getRegisterValue(address, reg);
		if (value != null) {
			final Address typeAddress = toAddr(value.getValue());
			if (currentManager.isTypeInfo(typeAddress)) {
				return currentManager.getType(typeAddress);
			}
		}
		value = prop.getRegisterValue(getDelayAddress(address), reg);
		if (value != null) {
			final Address typeAddress = toAddr(value.getValue());
			if (currentManager.isTypeInfo(typeAddress)) {
				return currentManager.getType(typeAddress);
			}
		}
		return null;
	}

	private void doDynamicCast(Address address) throws Exception {
		final Function function = getFunctionContaining(address);
		final SymbolicPropogator prop = analyzeFunction(function, analyzer, monitor);
		final ClassTypeInfo srcType = getType(prop, address, srcReg);
		final ClassTypeInfo destType = getType(prop, address, destReg);
		if (srcType != null && destType != null) {
			overrideFunction(function, address, srcType, destType);
		}
	}

	private static ParameterDefinition getParameter(DataType dataType) {
		final DataType dt = PointerDataType.getPointer(dataType, -1);
		return new ParameterDefinitionImpl(null, dt, null);
	}

	private FunctionDefinition getFunctionSignature(ClassTypeInfo src, ClassTypeInfo dest,
		Function function)
			throws Exception {
				final FunctionDefinition def = new FunctionDefinitionDataType(sig);
				final DataType destType = PointerDataType.getPointer(dest.getClassDataType(), -1);
				final ParameterDefinition[] params = def.getArguments();
				params[0] = getParameter(src.getClassDataType());
				params[1] = getParameter(src.getDataType());
				params[2] = getParameter(dest.getDataType());
				def.setName(TMP_NAME);
				def.setArguments(params);
				def.setReturnType(destType);
				return def;
	}

	private void overrideFunction(Function function, Address address,
		ClassTypeInfo src, ClassTypeInfo dest) throws Exception {
			int transaction = -1;
			if (currentProgram.getCurrentTransaction() == null) {
				transaction = currentProgram.startTransaction("Override Signature");
			}
			boolean commit = false;
			try {
				final FunctionDefinition def = getFunctionSignature(src, dest, function);
				if (def != null) {
					DataTypeSymbol symbol = new DataTypeSymbol(def, NAME_ROOT, AUTO_CAT);
					Namespace space = HighFunction.findCreateOverrideSpace(function);
					if (space != null) {
						symbol.writeSymbol(table, address, space, dtm, true);
						commit = true;
					}
				}
			}
			catch (Exception e) {
				printerr("Error overriding signature: " + e.getMessage());
			}
			finally {
				if (transaction != -1) {
					currentProgram.endTransaction(transaction, commit);
				}
			}
	}

		// These should be in a Util class somewhere!

		private static ConstantPropagationAnalyzer getConstantAnalyzer(Program program) {
			AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(program);
			List<ConstantPropagationAnalyzer> analyzers =
				ClassSearcher.getInstances(ConstantPropagationAnalyzer.class);
			for (ConstantPropagationAnalyzer analyzer : analyzers) {
				if (analyzer.canAnalyze(program)) {
					return (ConstantPropagationAnalyzer) mgr.getAnalyzer(analyzer.getName());
				}
			}
			return null;
		}

		public static SymbolicPropogator analyzeFunction(Function function,
			ConstantPropagationAnalyzer analyzer, TaskMonitor monitor) throws CancelledException {
				Program program = function.getProgram();
				SymbolicPropogator symEval = new SymbolicPropogator(program);
				symEval.setParamRefCheck(true);
				symEval.setReturnRefCheck(true);
				symEval.setStoredRefCheck(true);
				analyzer.flowConstants(program, function.getEntryPoint(), function.getBody(),
									   symEval, monitor);
				return symEval;
		}
}
