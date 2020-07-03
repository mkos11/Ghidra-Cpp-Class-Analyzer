package ghidra.app.plugin.prototype.CppCodeAnalyzerPlugin;

import java.util.*;
import java.util.stream.Collectors;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.Vtable;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighParam;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.symbol.Reference;
import ghidra.util.exception.AssertException;

import cppclassanalyzer.data.typeinfo.ClassTypeInfoDB;
import cppclassanalyzer.decompiler.DecompilerAPI;
import cppclassanalyzer.decompiler.function.HighFunctionCall;
import cppclassanalyzer.decompiler.function.HighFunctionCallParameter;
import cppclassanalyzer.utils.CppClassAnalyzerUtils;
import util.CollectionUtils;

public abstract class AbstractDecompilerBasedConstructorAnalysisCmd
		extends AbstractConstructorAnalysisCmd {

	private final DecompilerAPI api;

	protected AbstractDecompilerBasedConstructorAnalysisCmd(String name, DecompilerAPI api) {
		super(name);
		this.api = api;
	}

	protected AbstractDecompilerBasedConstructorAnalysisCmd(String name, ClassTypeInfo typeinfo,
			DecompilerAPI api) {
		super(name, typeinfo);
		this.api = api;
	}

	@Override
	protected boolean analyze() throws Exception {
		if (!type.hasParent()) {
			return false;
		}
		Vtable vtable = type.getVtable();
		if (!Vtable.isValid(vtable)) {
			return false;
		}
		for (ClassFunction function : getFunctions()) {
			monitor.checkCanceled();
			if (function.function.isThunk()) {
				continue;
			}
			boolean success = false;
			try {
				HighFunction hf = api.getHighFunction(function.function);
				HighParam thisParam = hf.getLocalSymbolMap().getParam(0);
				List<HighFunctionCall> statements = api.getFunctionCalls(function.function);
				if (type.getParentModels().length < statements.size()) {
					continue;
				}
				if (function.isDestructor()) {
					success = processDestructor(thisParam, function.getFunction(), statements);
				} else {
					success = processConstructor(thisParam, function.getFunction(), statements);
				}
			} finally {
				if (success) {
					setFunction(type, function.function, function.isDestructor());
				}
			}
		}
		return true;
	}

	private boolean processDestructor(HighParam thisParam, Function function,
			List<HighFunctionCall> calls) throws Exception {
		// The in-charge destructor must end with all
		// parents destructors + return. No exceptions.
		ClassTypeInfo[] parents = type.getParentModels();
		int end = calls.size() - 1;
		int start = end - parents.length;
		List<HighFunctionCall> destructorCalls = calls.subList(start, end);
		if (destructorCalls.size() != parents.length) {
			throw new AssertException("Start and end indexes aren't correct");
		}
		return setFunctions(thisParam, destructorCalls, false);
	}

	private boolean processConstructor(HighParam thisParam, Function function,
			List<HighFunctionCall> calls) throws Exception {
		// The in-charge constructor must start with all
		// parents constructors. No exceptions.
		ClassTypeInfo[] parents = type.getParentModels();
		int start = 0;
		int end = parents.length;
		List<HighFunctionCall> constructorCalls = calls.subList(start, end);
		if (constructorCalls.size() != parents.length) {
			throw new AssertException("Start and end indexes aren't correct");
		}
		return setFunctions(thisParam, constructorCalls, true);
	}

	private boolean setFunctions(HighParam thisParam, List<HighFunctionCall> calls,
			boolean isConstructor) throws Exception {
		for (HighFunctionCall call : calls) {
			List<HighFunctionCallParameter> params = call.getParameters();
			if (params.isEmpty()) {
				return false;
			}
			HighFunctionCallParameter self = params.get(0);
			if (!self.hasLocalRef()) {
				return false;
			}
			HighVariable var = self.getVariableToken().getHighVariable();
			if (var == null || !var.equals(thisParam)) {
				return false;
			}
			final int offset;
			if (self.hasFieldToken()) {
				offset = self.getOffset() + self.getFieldToken().getOffset();
			} else {
				offset = self.getOffset();
			}
			//var.
			//FunctionCallParser parser = new FunctionCallParser(statement);
			//Function fun = parser.getCalledFunction();
			//ClassTypeInfo parent = parser.getParentParameter();
			ClassTypeInfo parent = ((ClassTypeInfoDB) type).getBaseOffsets()
				.entrySet()
				.stream()
				.filter(e -> e.getValue().intValue() == offset)
				.findFirst()
				.map(Map.Entry::getKey)
				.orElse(null);
			if (parent == null) {
				return false;
			}
			Function fun = call.getFunction();
			if (fun.isThunk()) {
				fun = fun.getThunkedFunction(true);
			}
			setFunction(parent, fun, !isConstructor);
		}
		return true;
	}

	private List<ClassFunction> getFunctions() {
		Vtable vtable = type.getVtable();
		if (!Vtable.isValid(vtable)) {
			return Collections.emptyList();
		}
		Address[] tableAddresses = vtable.getTableAddresses();
		if (tableAddresses.length == 0) {
			// no virtual functions, nothing to analyze.
			return Collections.emptyList();
		}
		Data data = listing.getDataContaining(tableAddresses[0]);
		if (data == null) {
			String msg = String.format(
				"Vtable data for %s at %s has been deleted",
				type.getFullName(),
				tableAddresses[0]);
			throw new AssertException(msg);
		}
		return CollectionUtils.asStream(data.getReferenceIteratorTo())
			.filter(r -> r.getReferenceType().isData())
			.map(Reference::getFromAddress)
			.map(listing::getFunctionContaining)
			.filter(Objects::nonNull)
			.filter(CppClassAnalyzerUtils::isDefaultFunction)
			.map(f -> new ClassFunction(f, vtable.containsFunction(f)))
			.collect(Collectors.toList());
	}

	protected static class ClassFunction {

		private final Function function;
		private final boolean isDestructor;

		public ClassFunction(Function function, boolean isDestructor) {
			this.function = function;
			this.isDestructor = isDestructor;
		}

		protected Function getFunction() {
			return function;
		}

		protected boolean isDestructor() {
			return isDestructor;
		}
	}
}
