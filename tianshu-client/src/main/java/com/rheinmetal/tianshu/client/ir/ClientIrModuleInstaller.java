package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.IrModule;
import com.rheinmetal.tianshu.function.ir.IrModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class ClientIrModuleInstaller extends IrModuleInstaller {
    private final ClientNamedObjectIndexManager indexManager;

    public ClientIrModuleInstaller(ModuleRuntimeAccess moduleRuntime, ClientNamedObjectIndexManager indexManager) {
        super(moduleRuntime);
        this.indexManager = java.util.Objects.requireNonNull(indexManager, "indexManager");
    }

    @Override
    protected IrModule createModule() {
        return new IrModule(moduleRuntime, new ClientIrNamedObjectEnhancer(indexManager));
    }
}
