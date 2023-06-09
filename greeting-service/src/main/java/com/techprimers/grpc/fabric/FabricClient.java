package com.techprimers.grpc.fabric;

import org.apache.commons.codec.binary.Hex;
import org.hyperledger.fabric.protos.ledger.rwset.kvrwset.KvRwset;
import org.hyperledger.fabric.sdk.BlockEvent.TransactionEvent;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.InvalidProtocolBufferRuntimeException;
import org.hyperledger.fabric.sdk.exception.NetworkConfigurationException;
import org.hyperledger.fabric.sdk.exception.TransactionException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.Attribute;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hyperledger.fabric.sdk.BlockInfo.EnvelopeType.TRANSACTION_ENVELOPE;

public class FabricClient {
    private static final String channelName = "mychannel";
    private static final String chaincodeName = "fabcar";
    private static final String mspIdemix = "idemixMSPID1";

    private final NetworkConfig config;
    private final HFCAClient caClient;

    private HFClient client = null;
    private HFClient clientIdemix = null;

    public FabricClient(NetworkConfig config) {
        this.config = config;
        NetworkConfig.CAInfo info = config.getClientOrganization().getCertificateAuthorities().get(0);
        this.caClient = this.getHfCaClient(info.getUrl(), info.getProperties());
    }

    public String getAllCars(AppUser appUser, String votingId) throws Exception {
        return queryBlockChain(appUser, false, "queryAllCars", votingId);
    }


    public void registerUserInVotingIdemix(AppUser appUser, JSONObject voting) throws Exception {

        BigInteger signedKey = getBlindSignature(appUser, voting);// create a signature for voting
        Storage.saveSignedKey(appUser.getName(), signedKey);

        invokeRegistration(appUser, voting.getString("id"), signedKey, Storage.getPubKey(appUser.getName()));
    }

    public String voteIdemix(AppUser appUser, JSONObject ballot) throws Exception {
        RSAPublicKey myPubKey = Storage.getPubKey(appUser.getName());
        RSAPrivateKey myPrivKey = Storage.getPrivKey(appUser.getName());

        byte[] myHash = getHashOfPubKey(myPubKey);
        BigInteger hashB = new BigInteger(1, myHash);
        BigInteger encrHash = hashB.modPow(myPrivKey.getPrivateExponent(), myPrivKey.getModulus());

        String encodedPubKey = Base64.getEncoder().encodeToString(myPubKey.getEncoded());
        ballot
                .put("key", encodedPubKey)
                .put("signedKey", encrHash.toString(10));

        return invokeBlockChain(appUser, true, "voteIdemix", ballot.toString());
    }

    public String invokeInitLedeger(AppUser appUser) throws Exception {
        return invokeBlockChain(appUser, false, "initLedger", null);
    }

    private BigInteger getBlindSignature(AppUser appUser, JSONObject voting) throws Exception {
        RSAPublicKey myPubKey = Storage.getPubKey(appUser.getName());
        KeyFactory kf = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec keySpecX509 = new X509EncodedKeySpec(Base64.getDecoder().decode(voting.getString("pubKey")));
        RSAPublicKey votingPubKey = (RSAPublicKey) kf.generatePublic(keySpecX509);

        BigInteger R = getRandom(8);
        BigInteger RInv = R.modInverse(votingPubKey.getModulus());
        String data = getDataForBlindSign(votingPubKey, myPubKey, R);// disguise data === m'

        JSONObject userRegData = new JSONObject()
                .put("userId", appUser.getName())
                .put("votingId", voting.getString("id"))
                .put("data", data);

        BigInteger signedData = new BigInteger(// get s'
                invokeBlockChain(appUser, false, "getBlindSign", userRegData.toString()));
        BigInteger signedKey = signedData.multiply(RInv);// get s

        // check
        BigInteger myHashForCheck = signedKey.modPow(votingPubKey.getPublicExponent(), votingPubKey.getModulus());
        byte[] myHash = getHashOfPubKey(myPubKey);
        BigInteger hashB = new BigInteger(1, myHash);

        if (hashB.compareTo(myHashForCheck) != 0) {
            throw new Exception("Hashs are not the same. blind signature is invalid");
        }

        return signedKey;
    }

    private String invokeRegistration(AppUser appUser, String votingId, BigInteger signedKey, RSAPublicKey publicKey)
            throws Exception {

        String encodedPubKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        /** containing {votingID, userPubKey, userSignedKey} */
        JSONObject userTallingData = new JSONObject().put("votingId", votingId).put("key", encodedPubKey)
                .put("signedKey", signedKey.toString(10));
        /** registering a composition key to connect voting and user */
        return invokeBlockChain(appUser, true, "registerUserInVotingIdemix", userTallingData.toString());
    }

    /**
     *
     * @param pubKey
     * @param myPubKey
     * @param R
     * @return return m' based on user public key
     * @throws IOException
     * @throws NoSuchAlgorithmException
     */
    private String getDataForBlindSign(RSAPublicKey pubKey, RSAPublicKey myPubKey, BigInteger R)
            throws IOException, NoSuchAlgorithmException {
        byte[] hash = getHashOfPubKey(myPubKey);
        BigInteger REncr = R.modPow(pubKey.getPublicExponent(), pubKey.getModulus());// R^e mod N
        BigInteger hashedKey = new BigInteger(1, hash);
        BigInteger multNum = REncr.multiply(hashedKey);// hash(m)*blindFactor

        return multNum.toString(10);
    }

    private byte[] getHashOfPubKey(RSAPublicKey pubKey) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(pubKey.getEncoded());
    }

    private BigInteger getRandom(int numBytes) {
        byte[] r = new byte[numBytes];
        new Random().nextBytes(r);
        return new BigInteger(1, r);
    }

    public HFCAClient getHfCaClient(String caUrl, Properties caClientProperties) {
        try {
            CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
            HFCAClient caClient = HFCAClient.createNewInstance(caUrl, caClientProperties);
            caClient.setCryptoSuite(cryptoSuite);
            return caClient;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
// func() throws Exception {} can act as a normal function, and throws error only if some errors occured in the body
    /**
     * Enroll an user
     * @param userId
     * @param org
     * @param msp
     * @param secret
     * @return return an enrolled user's appUser object
     * @throws Exception
     */
    public AppUser enrollUser(String userId, String org, String msp, String secret) throws
            Exception {
        Enrollment enrollment = caClient.enroll(userId, secret);
        AppUser appUser = new AppUser(userId, org, msp, enrollment);

        return appUser;
    }

    /**
     * Register a user
     * @param registrar
     * @param userId
     * @param org
     * @param isAdmin
     * @param secret
     * @return return an enrollment secret
     * @throws Exception
     */
    public String registerUser(AppUser registrar, String userId, String org, Boolean
            isAdmin, String secret)
            throws Exception {
        RegistrationRequest rr = new RegistrationRequest(userId, org);
        rr.setSecret(secret);

        return registerUser(registrar, rr, isAdmin);
    }

    private String registerUser(AppUser registrar, RegistrationRequest rr, boolean isAdmin) throws
            Exception {
        if (isAdmin) {
            Attribute attr = new Attribute("role", "2");
            rr.addAttribute(attr);
        }

        return caClient.register(rr, registrar);
    }

    private HFClient getHFClient(AppUser appUser, boolean isIdemix) throws Exception {
        if (isIdemix) {
            if (clientIdemix != null) {
                if (clientIdemix.getUserContext().getName().equals(appUser.getName())
                        && clientIdemix.getUserContext().getAffiliation().equals(appUser.getAffiliation())) {
                    return clientIdemix;
                }
            }
            clientIdemix = getClient(appUser, isIdemix);
            return clientIdemix;
        }

        if (client != null) {
            if (client.getUserContext().getEnrollment().equals(appUser.getEnrollment())) {
                return client;
            }
        }
        client = getClient(appUser, isIdemix);
        return client;
    }

    public HFClient getClient(AppUser appUser) throws Exception {
        HFClient client = getHfClient();
        client.setUserContext(appUser);
        getChannel(client);

        return client;
    }

    private Channel getChannel(HFClient client) throws InvalidArgumentException, TransactionException, NetworkConfigurationException {
        Channel channel = client.loadChannelFromConfig(channelName, config);
        channel.initialize();
        return channel;
    }

    private HFClient getHfClient() throws Exception {
        CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
        HFClient client = HFClient.createNewInstance();
        client.setCryptoSuite(cryptoSuite);
        return client;
    }

    public HFClient getClient(AppUser appUser, boolean isIdemix) throws Exception {
        if (isIdemix) {
            AppUser newAppUser = new AppUser(appUser.getName(), appUser.getAffiliation(), appUser.getMspId(),
                    caClient.idemixEnroll(appUser.getEnrollment(), mspIdemix));
            return getClient(newAppUser);
        }

        return getClient(appUser);
    }

    public AppUser getAppUserIdemix(AppUser appUser) throws Exception {
        AppUser newAppUser = new AppUser(appUser.getName(), appUser.getAffiliation(), appUser.getMspId(),
                caClient.idemixEnroll(appUser.getEnrollment(), mspIdemix));
        System.out.println(newAppUser.toString());
        return newAppUser;
    }

    public String invokeBlockChain(AppUser appUser, boolean isIdemix, String function, String... args) throws Exception {
        HFClient client = getHFClient(appUser, isIdemix);
        Channel channel = client.getChannel(channelName);

        TransactionProposalRequest tpr = client.newTransactionProposalRequest();
        ChaincodeID chaincodeID = ChaincodeID.newBuilder().setName(chaincodeName).build();
        tpr.setChaincodeID(chaincodeID);
        tpr.setFcn(function);
        tpr.setArgs(args);



        Collection<ProposalResponse> resps = channel.sendTransactionProposal(tpr, getClientPeers(channel));
        byte[] response = handlePorposalResponses(resps);

        CompletableFuture<TransactionEvent> future = channel.sendTransaction(resps,
                Channel.TransactionOptions.createTransactionOptions()
                        .nOfEvents(Channel.NOfEvents.createNofEvents()
                                .setN(1)
                                .addEventHubs(channel.getEventHubs())
                                .addPeers(channel.getPeers())
                        )
        );

        future.thenAccept(result -> {
            System.out.println("ok");
        }).get();

        return response == null ? null : new String(response);
    }

    private List<Peer> getClientPeers(Channel channel) throws Exception {
        List<Peer> orgPeers = new ArrayList<>();
        for (Peer peer : channel.getPeers()) {
            if (config.getClientOrganization().getPeerNames().contains(peer.getName())) {
                orgPeers.add(peer);
            }
        }

        if (orgPeers.isEmpty()) {
            throw new Exception("Not found any client org peer");
        }

        return orgPeers;
    }

    public String queryBlockChain(AppUser appUser, boolean isIdemix, String function, String... args)
            throws Exception {
        System.out.println(appUser.toString());
        HFClient client = getHFClient(appUser, isIdemix);
        System.out.println("User context "+ client.getUserContext());
        Channel channel = client.getChannel(channelName);
        System.out.println("### channel " + channel.toString());

        QueryByChaincodeRequest qpr = client.newQueryProposalRequest();
        ChaincodeID chaincodeID = ChaincodeID.newBuilder().setName(chaincodeName).build();
        System.out.println("### chaincodeID " + chaincodeID);

        qpr.setChaincodeID(chaincodeID);
        qpr.setFcn(function);
        qpr.setArgs(args);
        System.out.println("### qpr: " + qpr);

        Collection<ProposalResponse> presps = channel.queryByChaincode(qpr, getClientPeers(channel));
        byte[] response = handlePorposalResponses(presps);

        return response == null ? null : new String(response);
    }


    public void readBlocks(AppUser appUser) throws Exception {
        try {
            HFClient client = getHFClient(appUser, false);
            Channel channel = client.getChannel(channelName);
            Peer peer = getClientPeers(channel).get(0);
            BlockchainInfo channelInfo = channel.queryBlockchainInfo(peer);

            for (long current = 0; current <  channelInfo.getHeight(); current++) {
                BlockInfo returnedBlock = channel.queryBlockByNumber(peer, current);
                final long blockNumber = returnedBlock.getBlockNumber();

                System.out.println(String.format("current block number %d ", blockNumber));
                System.out.println(String.format(" data hash: %s", Hex.encodeHexString(returnedBlock.getDataHash())));
                System.out.println(String.format(" previous hash id: %s", Hex.encodeHexString(returnedBlock.getPreviousHash())));
                System.out.println(String.format(" calculated block hash is %s", Hex.encodeHexString(SDKUtils.calculateBlockHash(client,
                        blockNumber, returnedBlock.getPreviousHash(), returnedBlock.getDataHash()))));

                System.out.println(String.format(" has %d envelope count:", returnedBlock.getEnvelopeCount()));
                int i = 0;
                for (BlockInfo.EnvelopeInfo envelopeInfo : returnedBlock.getEnvelopeInfos()) {
                    ++i;

                    System.out.println(String.format("  Transaction number %d has transaction id: %s", i, envelopeInfo.getTransactionID()));
                    System.out.println(String.format("   channel id: %s", envelopeInfo.getChannelId()));
                    System.out.println(String.format("   transaction timestamp: %tB %<te,  %<tY  %<tT %<Tp", envelopeInfo.getTimestamp()));
                    System.out.println(String.format("   type id: %s", "" + envelopeInfo.getType()));
                    System.out.println(String.format("   nonce : %s", "" + Hex.encodeHexString(envelopeInfo.getNonce())));
                    System.out.println(String.format("   submitter mspid: %s", envelopeInfo.getCreator().getMspid()));

                    if (envelopeInfo.getType() == TRANSACTION_ENVELOPE) {
                        BlockInfo.TransactionEnvelopeInfo transactionEnvelopeInfo = (BlockInfo.TransactionEnvelopeInfo) envelopeInfo;

                        System.out.println(String.format("   has %d actions", transactionEnvelopeInfo.getTransactionActionInfoCount()));
                        System.out.println(String.format("   isValid %b", transactionEnvelopeInfo.isValid()));
                        System.out.println(String.format("   validation code %d", transactionEnvelopeInfo.getValidationCode()));

                        int j = 0;
                        for (BlockInfo.TransactionEnvelopeInfo.TransactionActionInfo transactionActionInfo : transactionEnvelopeInfo.getTransactionActionInfos()) {
                            ++j;
                            System.out.println(String.format("    Transaction action %d", j));
                            System.out.println(String.format("    response status %d", transactionActionInfo.getResponseStatus()));
                            System.out.println(String.format("    response message bytes as string: %s",
                                    printableString(new String(transactionActionInfo.getResponseMessageBytes(), UTF_8))));
                            System.out.println(String.format("    has %d endorsements", transactionActionInfo.getEndorsementsCount()));

                            for (int n = 0; n < transactionActionInfo.getEndorsementsCount(); ++n) {
                                BlockInfo.EndorserInfo endorserInfo = transactionActionInfo.getEndorsementInfo(n);
                                System.out.println(String.format("     Endorser %d endorser: mspid %s ", n, endorserInfo.getMspid()));
                            }
                            System.out.println(String.format("    has %d chaincode input arguments", transactionActionInfo.getChaincodeInputArgsCount()));
                            for (int z = 0; z < transactionActionInfo.getChaincodeInputArgsCount(); ++z) {
                                System.out.println(String.format("      has chaincode input argument %d is: %s", z,
                                        printableString(new String(transactionActionInfo.getChaincodeInputArgs(z), UTF_8))));
                            }

                            System.out.println(String.format("    proposal response status: %d",
                                    transactionActionInfo.getProposalResponseStatus()));
                            System.out.println(String.format("    proposal response payload: %s",
                                    printableString(new String(transactionActionInfo.getProposalResponsePayload()))));

                            String chaincodeIDName = transactionActionInfo.getChaincodeIDName();
                            String chaincodeIDVersion = transactionActionInfo.getChaincodeIDVersion();
                            String chaincodeIDPath = transactionActionInfo.getChaincodeIDPath();
                            System.out.println(String.format("    proposal chaincodeIDName: %s, chaincodeIDVersion: %s,  chaincodeIDPath: %s ",
                                    chaincodeIDName, chaincodeIDVersion, chaincodeIDPath));

                            TxReadWriteSetInfo rwsetInfo = transactionActionInfo.getTxReadWriteSet();
                            if (null != rwsetInfo) {
                                System.out.println(String.format("    has %d name space read write sets", rwsetInfo.getNsRwsetCount()));

                                for (TxReadWriteSetInfo.NsRwsetInfo nsRwsetInfo : rwsetInfo.getNsRwsetInfos()) {
                                    final String namespace = nsRwsetInfo.getNamespace();
                                    KvRwset.KVRWSet rws = nsRwsetInfo.getRwset();

                                    int rs = -1;
                                    for (KvRwset.KVRead readList : rws.getReadsList()) {
                                        rs++;

                                        System.out.println(String.format("      Namespace %s read set %d key %s  version [%d:%d]", namespace, rs, readList.getKey(),
                                                readList.getVersion().getBlockNum(), readList.getVersion().getTxNum()));

                                    }

                                    rs = -1;
                                    for (KvRwset.KVWrite writeList : rws.getWritesList()) {
                                        rs++;
                                        String valAsString = printableString(new String(writeList.getValue().toByteArray(), UTF_8));

                                        System.out.println(String.format("      Namespace %s write set %d key %s has value '%s' ", namespace, rs, writeList.getKey(), valAsString));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (InvalidProtocolBufferRuntimeException e) {
            throw e.getCause();
        }
    }
    static String printableString(final String string) {
        int maxLogStringLength = 64;
        if (string == null || string.length() == 0) {
            return string;
        }

        String ret = string.replaceAll("[^\\p{Print}]", "?");

        ret = ret.substring(0, Math.min(ret.length(), maxLogStringLength)) + (ret.length() > maxLogStringLength ? "..." : "");

        return ret;

    }

    private byte[] handlePorposalResponses(Collection<ProposalResponse> presps) throws Exception {
        byte[] response = null;

        for (ProposalResponse pres : presps) {
            if (pres.getStatus() != ProposalResponse.Status.SUCCESS) {
                throw new Exception(pres.getMessage());
            }
            System.out.println(new String(pres.getChaincodeActionResponsePayload()));
            if (response == null) {
                response = pres.getChaincodeActionResponsePayload();
                continue;
            }

            if (!Arrays.equals(response, pres.getChaincodeActionResponsePayload())) {
                throw new Exception("Proposal responses are not the same");
            }
        }

        return response;
    }
}
