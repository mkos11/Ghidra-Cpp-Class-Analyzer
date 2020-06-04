package cppclassanalyzer.data.typeinfo;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.program.database.DatabaseObject;
import cppclassanalyzer.data.ClassTypeInfoManager;
import cppclassanalyzer.data.manager.recordmanagers.ArchiveRttiRecordManager;
import cppclassanalyzer.data.manager.recordmanagers.ProgramRttiRecordManager;
import ghidra.program.database.map.AddressMap;

import cppclassanalyzer.database.record.ArchivedClassTypeInfoRecord;
import cppclassanalyzer.database.record.ClassTypeInfoRecord;

public abstract class ClassTypeInfoDB extends DatabaseObject implements ClassTypeInfo {

	public static final long INVALID_KEY = AddressMap.INVALID_ADDRESS_KEY;

	ClassTypeInfoDB(ProgramRttiRecordManager manager, ClassTypeInfoRecord record) {
		super(manager.getTypeCache(), record.getKey());
	}

	ClassTypeInfoDB(ProgramRttiRecordManager manager, long key) {
		super(manager.getTypeCache(), key);
	}

	ClassTypeInfoDB(ArchiveRttiRecordManager manager, ArchivedClassTypeInfoRecord record) {
		super(manager.getTypeCache(), record.getKey());
	}

	public abstract ClassTypeInfoManager getManager();

	@Override
	public abstract ClassTypeInfoDB[] getParentModels();

	public abstract long getClassDataTypeId();

	public abstract boolean isModifiable();

}