// IVSLAMService.aidl
package com.theta360.vslam;

import com.theta360.vslam.IVSLAMServiceListener;

interface IVSLAMService {
    oneway void addListener(IVSLAMServiceListener listener);
    oneway void removeListener(IVSLAMServiceListener listener);

    int start(in String mapName, in int preview_width, in int preview_height, in SharedMemory sheredMem);
    int frame(in int offset, in int len);
    int stop();
}