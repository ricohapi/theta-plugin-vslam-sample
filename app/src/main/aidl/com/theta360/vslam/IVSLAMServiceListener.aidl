// IVSLAMServiceListener.aidl
package com.theta360.vslam;

oneway interface IVSLAMServiceListener {
   void onUpdatePossition(double x, double y, double z, double timeStamp, int status, long numLands, String message);
}