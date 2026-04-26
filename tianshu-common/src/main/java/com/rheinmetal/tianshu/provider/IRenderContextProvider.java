package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.MatrixSnapshot;
import com.rheinmetal.tianshu.snapshot.TooltipRect;

public interface IRenderContextProvider {

    TooltipRect getActiveTooltipRect();

    int getScreenWidth();

    int getScreenHeight();

    MatrixSnapshot getProjectionMatrix();

    MatrixSnapshot getModelViewMatrix();
}
