package com.techprimers.grpc.fabric;

import org.hyperledger.fabric.sdk.NetworkConfig;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class FabricClientSingleton {

    private static String admin;
    private static String secretA;
    private static String user;
    private static NetworkConfig.OrgInfo orgInfo;
    private static FabricClient fabricClient;
    private static AppUser appUser;

    @PostConstruct
    public void init() {
        try {
            NetworkConfig networkConfig = NetworkConfig.fromYamlFile(new File("greeting-service/src/main/resources/network-config.yaml"));
            FabricClientSingleton.orgInfo = networkConfig.getClientOrganization();
            NetworkConfig.UserInfo userInfo = orgInfo.getCertificateAuthorities().get(0).getRegistrars().iterator().next();
            FabricClientSingleton.admin = userInfo.getName();
            FabricClientSingleton.secretA = userInfo.getEnrollSecret();
            FabricClientSingleton.user = orgInfo.getName() + "User";
            FabricClientSingleton.fabricClient = new FabricClient(networkConfig);
            FabricClientSingleton.appUser = getAppUser(fabricClient);

        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }

    public FabricClient getFabricClient() { return fabricClient; }

    public AppUser getAppUser() { return appUser; }


    private static AppUser getAppUser(FabricClient fabricClient) throws Exception {
        AppUser appUser;
        if (!Storage.exist(orgInfo.getMspId(), admin)) {
            appUser = fabricClient.enrollUser(admin, orgInfo.getName(), orgInfo.getMspId(), secretA);
            Storage.save(appUser);
        }

        if (Storage.exist(orgInfo.getMspId(), user)) {
            return Storage.load(orgInfo.getMspId(), user);
        }

        AppUser registrar = Storage.load(orgInfo.getMspId(), admin);
        String secret = user;
        try {
            fabricClient.registerUser(registrar, user, orgInfo.getName(), false, secret);
            appUser = fabricClient.enrollUser(user, orgInfo.getName(), orgInfo.getMspId(), secret);
        } catch (Throwable ex) {
            appUser = fabricClient.enrollUser(user, orgInfo.getName(), orgInfo.getMspId(), secret);
        }

        Storage.save(appUser);

        return appUser;
    }

}
