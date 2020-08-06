package com.logicblox.steve.provision;


public interface ProvisionerInterface {

// Each cloud provider, needs to implement this interface.

    // Spot in AWS, Preemptible in GCE.
    int getNumberOfCurrentSpotInstances();

    int getNumberOfCurrentOnDemandInstances();

    void createOnDemandInstances(int nr);

    void createSpotInstances(int nr);
}
