package com.techprimers.grpc.service;

import com.techprimers.grpc.WorkloadRequest;
import com.techprimers.grpc.WorkloadResponse;
import com.techprimers.grpc.WorkloadServiceGrpc;
import com.techprimers.grpc.fabric.AppUser;
import com.techprimers.grpc.fabric.FabricClient;
import com.techprimers.grpc.fabric.FabricClientSingleton;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;

@GrpcService
public class WorkloadServiceImpl extends WorkloadServiceGrpc.WorkloadServiceImplBase {
    @Autowired
    private FabricClientSingleton fabricClientSingleton;

    private int cntCar = 100;
    @Override
    public void initBlockchain(WorkloadRequest request, StreamObserver<WorkloadResponse> responseObserver) {
        String message = request.getMessage();
        System.out.println("Received Message: " + message);

        FabricClient cli = fabricClientSingleton.getFabricClient();
        AppUser user = fabricClientSingleton.getAppUser();

        System.out.println(user.toString());

        String apiRecv = null;
        String apiRecv_init = null;
        try {
            apiRecv_init = cli.invokeBlockChain(user, false, "initLedger", "123");
            apiRecv = cli.queryBlockChain(user, false, "queryAllCars", "CAR12");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        WorkloadResponse greetingResponse = WorkloadResponse.newBuilder()
                .setMessage("Received: " + apiRecv + " and " + apiRecv_init + ". Hello From Server. ")
                .build();

        responseObserver.onNext(greetingResponse);
        responseObserver.onCompleted();
    }

    @Override
    public void queryAll(WorkloadRequest request, StreamObserver<WorkloadResponse> responseObserver) {
        String message = request.getMessage();
        System.out.println("Received Message: " + message);

        FabricClient cli = fabricClientSingleton.getFabricClient();
        AppUser user = fabricClientSingleton.getAppUser();

        System.out.println(user.toString());

        String apiRecv = null;
        String apiRecv_init = null;
        try {
            apiRecv = cli.queryBlockChain(user, false, "queryAllCars", "CAR12");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        WorkloadResponse greetingResponse = WorkloadResponse.newBuilder()
                .setMessage("Received: " + apiRecv + " and " + apiRecv_init + ". Hello From Server. ")
                .build();

        responseObserver.onNext(greetingResponse);
        responseObserver.onCompleted();
    }

    @Override
    public void create(WorkloadRequest request, StreamObserver<WorkloadResponse> responseObserver) {
        String message = request.getMessage();
        System.out.println("Received Message: " + message);

        FabricClient cli = fabricClientSingleton.getFabricClient();
        AppUser user = fabricClientSingleton.getAppUser();

        System.out.println(user.toString());

        String apiRecv = null;
        String apiRecv_init = null;
        try {
            String[] args = {"CAR"+(this.cntCar++), "Toyota", "A186", "white", "Jake"};
            apiRecv_init = cli.invokeBlockChain(user, false, "createCar", args);
            apiRecv = cli.queryBlockChain(user, false, "queryAllCars", "CAR12");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        WorkloadResponse greetingResponse = WorkloadResponse.newBuilder()
                .setMessage("Received: " + apiRecv + " and " + apiRecv_init + ". Hello From Server. ")
                .build();

        responseObserver.onNext(greetingResponse);
        responseObserver.onCompleted();
    }
}
