package com.logicblox.steve.provision;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.*;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.*;
import java.util.List;

public class GCEProvisioner implements ProvisionerInterface {

    static final String NETWORK_INTERFACE_CONFIG = "ONE_TO_ONE_NAT";
    static final String NETWORK_ACCESS_CONFIG = "External NAT";
    static final String GOOGLE_API_ENDPOINT = "https://www.googleapis.com/compute/v1/projects/";

    Compute computeService;

    public GCEProvisioner(CommandLineArguments cmdArgs){
        this.setCmdArgs(cmdArgs);
        try {
            this.computeService = createComputeService();
        } catch (Exception e) {
            System.err.println("Could not connect to Google compute service");
            e.printStackTrace();
        }
    }

    CommandLineArguments cmdArgs;

    public static Compute createComputeService() throws IOException, GeneralSecurityException {
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();

        GoogleCredential credential = GoogleCredential.getApplicationDefault();
        if (credential.createScopedRequired()) {
            credential =
                    credential.createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));
        }

        return new Compute.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName("lb-jobs")
                .build();
    }

    private Instance createInstance(String project, String zone, String machineType, String image, boolean preemptible){

        final String IMAGE_URI = GOOGLE_API_ENDPOINT + project + "/global/images/" + image;

        String instanceName = "lb-jobs-worker-"  + UUID.randomUUID().toString();

        Instance instance = new Instance();
        instance.setName(instanceName);

        Scheduling schedule = new Scheduling();
        schedule.setPreemptible(preemptible);
        instance.setScheduling(schedule);

        instance.setMachineType( GOOGLE_API_ENDPOINT + project + "/zones/" + zone + "/machineTypes/" + machineType);

        // TODO: Use network interface with more restriction to ingres connections.
        NetworkInterface ifc = new NetworkInterface();
        ifc.setNetwork(GOOGLE_API_ENDPOINT + project + "/global/networks/default");
        List<AccessConfig> configs = new ArrayList<>();
        AccessConfig config = new AccessConfig();
        config.setType(NETWORK_INTERFACE_CONFIG);
        config.setName(NETWORK_ACCESS_CONFIG);
        configs.add(config);
        ifc.setAccessConfigs(configs);
        instance.setNetworkInterfaces(Collections.singletonList(ifc));

        // Setup the metadata of the instance to include worker args.
        Metadata metadata = new Metadata();
        Metadata.Items startupScript = new Metadata.Items();
        startupScript.setKey("startup-script");
        startupScript.setValue(this.getMetadata());

        metadata.setItems(Collections.singletonList(startupScript));
        instance.setMetadata(metadata);

        // Setup service account for the instance with scopes allowing
        // self deletion which is needed when the instance is idle.

        ServiceAccount serviceAccount = new ServiceAccount();
        //FIXME: make configurable
        serviceAccount.setEmail("nixops-dashboard-dev@infor-faroi-dev.iam.gserviceaccount.com");
        serviceAccount.setScopes(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));
        List<ServiceAccount> serviceAccounts = new ArrayList<>();
        serviceAccounts.add(serviceAccount);
        instance.setServiceAccounts(serviceAccounts);

        // Labels
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("queue", this.cmdArgs.getInstanceType());
        instance.setLabels(labels);

        // Add attached Persistent Disk to be used by VM Instance, also add one local-ssd.
        AttachedDisk disk = new AttachedDisk();
        disk.setBoot(true);
        disk.setAutoDelete(true);
        disk.setType("PERSISTENT");
        AttachedDiskInitializeParams params = new AttachedDiskInitializeParams();
        params.setDiskName(instanceName);
        params.setSourceImage(IMAGE_URI);
        params.setDiskType(GOOGLE_API_ENDPOINT + project + "/zones/"
                + zone + "/diskTypes/pd-ssd");
        disk.setInitializeParams(params);

        AttachedDisk localSSD = new AttachedDisk();
        localSSD.setBoot(false);
        localSSD.setAutoDelete(true);
        localSSD.setType("SCRATCH");
        localSSD.setInterface("nvme");
        AttachedDiskInitializeParams localSSDParams = new AttachedDiskInitializeParams();
        localSSDParams.setDiskType(GOOGLE_API_ENDPOINT + project + "/zones/"
                + zone + "/diskTypes/local-ssd");
        localSSD.setInitializeParams(localSSDParams);

        List<AttachedDisk> disks = new ArrayList<>();
        disks.add(disk);
        disks.add(localSSD);

        List<String> tags_list = new ArrayList<String>();
        tags_list.add("worker");
        Tags tags = new Tags();
        tags.setItems(tags_list);
        instance.setTags(tags);

        instance.setDisks(disks);
        return instance;

    };

    private void createInstances(int nr, boolean preemptible){

        for(int i = 0; i<nr; i++) {
            try {

                Instance instance = this.createInstance(this.cmdArgs.getProject(), this.cmdArgs.getRegion(), this.cmdArgs.getInstanceType(), this.cmdArgs.getAmi(), preemptible);

                Compute.Instances.Insert request = computeService.instances().insert(this.cmdArgs.getProject(), this.cmdArgs.getRegion(), instance);

                Operation response = request.execute();

                // TODO: Log when we fail creating an instance.
                System.out.println(response.getStatus());
            }
            catch (IOException e) {
                e.printStackTrace();

            }
        }
    }

    public void createSpotInstances(int nr) {
        System.err.println(String.format("Creating %d spot instances", nr));

        if (cmdArgs.isDryRun()){
            System.out.println("Dry run detected.");
            return;
        }

        this.createInstances(nr, true);
    }

    public void createOnDemandInstances(int nr) {
        System.err.println(String.format("Creating %d on-demand instances", nr));
        if (cmdArgs.isDryRun()) {
            System.out.println("Dry run detected.");
            return;
        }
        this.createInstances(nr, false);
    }

    private String getFilters(Boolean preemptible ){
        StringBuilder filtersBuilder = new StringBuilder();
        // Always filter by the queue
        filtersBuilder
                .append(String.format("(labels.queue = %s)", this.cmdArgs.getInstanceType()))
                .append(String.format(" AND (scheduling.preemptible = %s)", preemptible ? "true" : "false"));
        return filtersBuilder.toString();
    }

    public int getNumberOfRunningInstances(boolean preemptible){

        try{
            Compute.Instances.List request = computeService.instances().list(cmdArgs.getProject(), cmdArgs.getRegion() );
            request.setFilter(this.getFilters(preemptible));

            InstanceList instanceList = request.execute();
            List<Instance> instances = instanceList.getItems();

            if (instances == null || instances.isEmpty()){
                System.out.println("No instance is running in the account.");
                return 0;
            }
            // TODO: Responses from GCP API are paginated we probably need to go through all pages in order to get correct number of running instances.
            return instances.size();

        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // get number of on-demand instances that are not yet terminated
    public int getNumberOfCurrentOnDemandInstances() {
        return getNumberOfRunningInstances(false);
    }

    // get number of spot instances that are not yet terminated
    public int getNumberOfCurrentSpotInstances() {
        return getNumberOfRunningInstances(true);
    }

    public CommandLineArguments getCmdArgs() {
        return cmdArgs;
    }

    public void setCmdArgs(CommandLineArguments cmdArgs) {
        this.cmdArgs = cmdArgs;
    }
    public String getMetadata(){
        return String.format("WORKERARGS=\"--bucket %s --incoming %s --outgoing %s --key-service %s\"",
                cmdArgs.getS3Bucket(),
                cmdArgs.getIncoming_url(),
                cmdArgs.getOutgoing_url(),
                cmdArgs.getServiceUri().toString());
    }

}
