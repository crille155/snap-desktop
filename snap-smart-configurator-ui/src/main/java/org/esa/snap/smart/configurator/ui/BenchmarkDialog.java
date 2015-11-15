/*
 * Copyright (C) 2015 CS SI
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.snap.smart.configurator.ui;

import com.bc.ceres.core.SubProgressMonitor;
import com.bc.ceres.core.VirtualDir;
import org.esa.snap.core.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.rcp.util.ProgressHandleMonitor;
import org.esa.snap.smart.configurator.Benchmark;
import org.esa.snap.smart.configurator.BenchmarkSingleCalculus;
import org.esa.snap.smart.configurator.ConfigurationOptimizer;
import org.esa.snap.smart.configurator.PerformanceParameters;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.common.WriteOp;
import org.esa.snap.core.gpf.internal.OperatorExecutor;
import org.esa.snap.core.gpf.internal.OperatorProductReader;
import org.esa.snap.core.gpf.ui.TargetProductSelectorModel;
import org.esa.snap.rcp.SnapDialogs;
import org.esa.snap.rcp.actions.file.SaveProductAsAction;
import org.esa.snap.ui.AppContext;
import org.netbeans.api.progress.ProgressUtils;

import javax.media.jai.JAI;
import java.io.File;

/**
 * Dialog to launch performance parameters benchmark.
 *
 * @author Manuel Campomanes
 */
public class BenchmarkDialog extends DefaultSingleTargetProductDialog {

    /**
     * Benchmark calculus model
     */
    private Benchmark benchmarkModel;


    /**
     * Parent panel
     */
    private PerformancePanel perfPanel;

    /**
     * Constructor
     *
     * @param perfPanel Parent JPanel
     * @param operatorName Operator name
     * @param benchmarkModel Benchmark model
     * @param appContext Application context
     */
    public BenchmarkDialog(PerformancePanel perfPanel, String operatorName, Benchmark benchmarkModel, AppContext appContext){
        super(operatorName, appContext, "Benchmark "+operatorName, null, false);

        this.benchmarkModel = benchmarkModel;
        this.getJDialog().setModal(true);
        this.perfPanel = perfPanel;
    }


        protected void executeOperator(Product targetProduct, ProgressHandleMonitor pm) throws Exception {
            final TargetProductSelectorModel model = getTargetProductSelector().getModel();

            Operator execOp = null;
            if (targetProduct.getProductReader() instanceof OperatorProductReader) {
                final OperatorProductReader opReader = (OperatorProductReader) targetProduct.getProductReader();
                Operator operator = opReader.getOperatorContext().getOperator();
                boolean autoWriteDisabled = operator.getSpi().getOperatorDescriptor().isAutoWriteDisabled();
                if (autoWriteDisabled) {
                    execOp = operator;
                }
            }

            if (execOp == null) {
                WriteOp writeOp = new WriteOp(targetProduct, model.getProductFile(), model.getFormatName());
                writeOp.setDeleteOutputOnFailure(true);
                writeOp.setWriteEntireTileRows(true);
                writeOp.setClearCacheAfterRowWrite(false);
                execOp = writeOp;
            }
            final OperatorExecutor executor = OperatorExecutor.create(execOp);
            executor.execute(SubProgressMonitor.create(pm, 100));
        }


    private void executeBenchmarks(ProgressHandleMonitor pm) throws Exception {
        pm.beginTask("Benchmark running... ", this.benchmarkModel.getBenchmarkCalculus().size()*100);

        for (BenchmarkSingleCalculus benchmarkSingleCalcul : this.benchmarkModel.getBenchmarkCalculus()) {
            pm.getProgressHandle().progress(
                    String.format("Benchmarking ( tile size:%d , cache size:%d , nb threads:%d )",
                                  benchmarkSingleCalcul.getTileSize(),
                                  benchmarkSingleCalcul.getCacheSize(),
                                  benchmarkSingleCalcul.getNbThreads()));

            final Product targetProduct;
            try {
                targetProduct = createTargetProduct();

            } catch (Throwable t) {
                handleInitialisationError(t);
                throw t;
            }
            if (targetProduct == null) {
                throw new NullPointerException("Target product is null.");
            }
            //load performance parameters for current benchmark
            this.benchmarkModel.loadBenchmarkPerfParams(benchmarkSingleCalcul);

            //processing start time
            long startTime = System.currentTimeMillis();

            executeOperator(targetProduct, pm);

            //save execution time
            benchmarkSingleCalcul.setExecutionTime(System.currentTimeMillis() - startTime);

            // we remove all tiles
            JAI.getDefaultInstance().setTileCache(JAI.createTileCache());
        }

        pm.done();
    }

    @Override
    protected void onApply() {
        //temporary directory for benchmark
        String tmpdirPath = System.getProperty("java.io.tmpdir") + "/snap-benchmark-tmp";
        appContext.getPreferences().setPropertyString(SaveProductAsAction.PREFERENCES_KEY_LAST_PRODUCT_DIR, tmpdirPath);
        //add current performance parameters to benchmark
        PerformanceParameters currentPerformanceParameters = ConfigurationOptimizer.getInstance().getActualPerformanceParameters();
        BenchmarkSingleCalculus currentBenchmarkSingleCalcul = new BenchmarkSingleCalculus(
                currentPerformanceParameters.getDefaultTileSize(),
                currentPerformanceParameters.getCacheSize(),
                currentPerformanceParameters.getNbThreads());

        this.benchmarkModel.addBenchmarkCalcul(currentBenchmarkSingleCalcul);
        //benchmark loop
        try {
            //launch processing with a progress bar
            ProgressHandleMonitor pm = ProgressHandleMonitor.create("Running benchmark");

            Runnable operation = () -> {
                try {
                    executeBenchmarks(pm);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            };

            ProgressUtils.runOffEventThreadWithProgressDialog(operation, "Benchmarking....",
                                                              pm.getProgressHandle(),
                                                              true,
                                                              50,
                                                              1000);

            //sort benchmark results and return the faster
            BenchmarkSingleCalculus bestBenchmarkSingleCalcul = this.benchmarkModel.getFasterBenchmarkSingleCalculus();

            SnapDialogs.showInformation("Benchmark results", this.benchmarkModel.toString(), null);
            //update parent panel with best values
            this.perfPanel.updatePerformanceParameters(bestBenchmarkSingleCalcul);

        } catch (Exception ex) {
            SystemUtils.LOG.severe("Could not perform benchmark: " + ex.getMessage());
        } finally {
            //load old params (before benchmark)
            this.benchmarkModel.loadBenchmarkPerfParams(currentBenchmarkSingleCalcul);
            //delete benchmark TMP directory
            VirtualDir.deleteFileTree(new File(tmpdirPath));
        }
    }
}
