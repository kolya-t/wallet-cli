package org.tron.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.crypto.Hash;
import org.tron.common.filesystem.SolidityFileUtil;
import org.tron.common.solc.CompilationResult;
import org.tron.common.solc.CompilationResult.ContractMetadata;
import org.tron.common.solc.SolidityCompiler;
import org.tron.common.utils.AbiUtil;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Utils;
import org.tron.core.exception.CipherException;
import org.tron.core.exception.TronException;
import org.tron.keystore.WalletFile;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.walletserver.AutoClient;
import org.tron.walletserver.WalletApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import static org.tron.common.solc.SolidityCompiler.Options.*;


public class AutoDeployer {

    protected static final Logger logger = LoggerFactory.getLogger(AutoDeployer.class);
    protected static final AutoClient cli = new AutoClient();

    protected static String testerPswd = "tron@test^123";

    protected static final AutoClient bankClient = new AutoClient();
    static {
        try {
            bankClient.loadWalletFile("Star@2018", "TFVCqLPSCLUz9pFabt37widUMY7Hzec8UP");
            Account account = bankClient.queryAccount();
            logger.info("admin balance: " + account.getBalance());
        } catch (IOException | CipherException e) {
            logger.error("init bank error", e);
        }
    }

    protected static Long dropBalance(AutoClient cli, WalletFile user, long needBalance) throws TronException, InterruptedException {

        Account account = cli.queryAccount();
        Long balance = account.getBalance();
        if (needBalance <= balance) {
            return balance;
        }

        String bankTxId = bankClient.sendCoin(user.getAddress(), needBalance - balance);
        if (bankTxId != null && bankTxId.length() > 0){
//            Thread.sleep(3_000);
            account = cli.queryAccount();
            balance = account.getBalance();
        }

        if (needBalance > balance){
            throw new TronException("address: " + user.getAddress() + " balance: " + balance + " need :" + needBalance);
        }
        return balance;
    }

    protected static TransactionInfo checkSignedTxId(String txId) throws TronException{
        Optional<TransactionInfo> result = AutoClient.getTransactionInfoById(txId);
        if (result.isPresent() && result.get().getResult().equals(TransactionInfo.code.SUCESS)) {
            logger.info("checkSignedTxId trx: " + txId);
            return result.get();
        }else{
            logger.info("checkSignedTxId trx: " + Utils.printTransactionInfo(result.get()));
            throw new TronException("checkSignedTxId error");
        }
    }

    protected static String deployContract(AutoClient cli, ContractMetadata contract, int feeLimit) throws TronException {
        String txId = cli.deployContract("GetCode", contract.abi, contract.bin, feeLimit, 0, 0, 10000, 0, "", null);
        return WalletApi.encode58Check(checkSignedTxId(txId).getContractAddress().toByteArray());
    }

    protected static Object triggerContract(AutoClient cli, String contractAddress, String funcSign, List<Object> params, long callValue) throws TronException {

        byte[] input = AbiUtil.parseMethod(funcSign, params, false);
        Object result = cli.triggerContract(contractAddress, callValue, input, 30000000, 0, null);
        if (result == null) {
            throw new TronException("Trigger Contract Error");
        }
        if (result instanceof String) {
            Optional<TransactionInfo> confirm = AutoClient.getTransactionInfoById((String) result);
            // 如果以后起多个进程, 可以采用 select * from l_account where mod(id, 2) = 1; 解决多进程冲突的问题
            if (confirm.isPresent() && confirm.get().getResult().equals(TransactionInfo.code.SUCESS)) {
                return confirm.get();
            }
        }
        return result;
    }

    protected static CompilationResult doCompile(String contractFileName, boolean optimize) throws IOException{
        try {
            SolidityCompiler.Result compilerResult = SolidityCompiler.compile(
                    SolidityFileUtil.getExistFile(contractFileName), optimize, true, ABI, BIN, HASHES, INTERFACE,
                    METADATA);
            return CompilationResult.parse(compilerResult.output);
        } catch (IOException e) {
            logger.error("test error", e);
            throw e;
        }
    }

    public static long bytesToLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        buffer.put(bytes, 0, bytes.length);
        buffer.flip();//need flip
        return buffer.getLong();
    }

    public static void dropAddressIssues(int issueCount, String address) throws TronException {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.add(Calendar.DAY_OF_MONTH, +1);
        long dayBefore = calendar.getTime().getTime();

        calendar.add(Calendar.YEAR, +1);
        long yearAfter = calendar.getTime().getTime();

        for (int i = 0; i < issueCount; i++) {
            try {
                WalletFile user = bankClient.generateAddress(testerPswd);
                cli.loadWalletFile(testerPswd, user);
                dropBalance(cli, user, 10_000_000_000L);
                String txId = cli.assetIssue("assertaa_" + i, 1000000,
                    1000, 100000, 0,
                    dayBefore, yearAfter, 0, "descaa_" + i, String.format("http://abc_%d.com.", i),
                    1000, 10000, null);
                TransactionInfo info = checkSignedTxId(txId);
                if (info == null){
                    throw new TronException("create issue exception: " + txId);
                }
                Account account = cli.queryAccount();
                String tokenId = account.getAssetIssuedID().toStringUtf8();

                cli.transferAsset(address, tokenId, 100000);

            } catch (CipherException | IOException | TronException | InterruptedException e) {
                e.printStackTrace();
                throw new TronException(e.getMessage());
            }
        }
    }

    public static void testSuicide(){
        try {
            int maxTokenSize = 10001;

            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new Date());
            calendar.add(Calendar.DAY_OF_MONTH, +1);
            long dayBefore = calendar.getTime().getTime();

            calendar.add(Calendar.YEAR, +1);
            long yearAfter = calendar.getTime().getTime();

            // 编译合约
//            CompilationResult compilationResult = doCompile("test01.sol", true);
//            CompilationResult.ContractMetadata c1 = compilationResult.getContract("GetCode");

            // 有1000个 asset 的账号
//            WalletFile userContract = bankClient.generateAddress(testerPswd);
//            cli.loadWalletFile(testerPswd, userContract);
//            dropBalance(cli, userContract, 10_000_000_000L);

            // 有1k 个 asset 的 contract
            int assetCount_1k = 0;
            String contract_1k = "TUkbxM8zLrwnzopvPyrXofsfqFZGyNA6RP";//deployContract(cli, c1, 1000_000_000);
            logger.info("deploy contract 1k:" + contract_1k);

            // 有2k 个 asset 的 contract
            int assetCount_2k = 0;
            String contract_2k = "TSnEnkqp9RwhLrQjPQ78A6n1gUVjbMwYyb";//deployContract(cli, c1, 1000_000_000);
            logger.info("deploy contract 2k:" + contract_2k);

            int offset = 6000;
            // 有5k 个 asset 的 contract
            int assetCount_5k = (5000 - offset)/10;
            String contract_5k = "TYLGVugYtABdK7KiGjwHtig4rrdBHqbm5L";//deployContract(cli, c1, 1000_000_000);
            logger.info("deploy contract 5k:" + contract_5k);

            // 有7k 个 asset 的 contract
            int assetCount_7k = (7000 - offset)/10;
            String contract_7k = "TRQCNDs8g6T748qRhuQjqTHfX4eArttxus";//deployContract(cli, c1, 1000_000_000);
            logger.info("deploy contract 7k:" + contract_7k);

            // 有1w 个 asset 的 contract
            int assetCount_1w = (10_000 - offset)/10;
            String contract_1w = "TKaEGpcVWtbWcxkDGyEKcw6av5PuYCmjyQ";//deployContract(cli, c1, 1000_000_000);
            logger.info("deploy contract 1w:" + contract_1w);


            int i = 0;
            while (maxTokenSize >= 0){
                ++i;
                WalletFile user = bankClient.generateAddress(testerPswd);
                cli.loadWalletFile(testerPswd, user);
                dropBalance(cli, user, 10_000_000_000L);
                String txId = cli.assetIssue("assert_" + i, 100000,
                    1000, 100000, 0,
                    dayBefore, yearAfter, 0, "desc_" + i, String.format("http://abc_%d.com.", i),
                    1000, 10000, null);
                TransactionInfo info = checkSignedTxId(txId);
                if (info != null){
                    Account account = cli.queryAccount();
                    String tokenId = account.getAssetIssuedID().toStringUtf8();

                    if (assetCount_1k > 0 && cli.transferAsset(contract_1k, tokenId, 10) != null){
                        assetCount_1k--;
                    }

                    if (assetCount_2k > 0 && cli.transferAsset(contract_2k, tokenId, 10) != null){
                        assetCount_2k--;
                    }

                    if (assetCount_5k > 0 && cli.transferAsset(contract_5k, tokenId, 10) != null){
                        assetCount_5k--;
                    }

                    if (assetCount_7k > 0 && cli.transferAsset(contract_7k, tokenId, 10) != null){
                        assetCount_7k--;
                    }

                    if (assetCount_1w > 0 && cli.transferAsset(contract_1w, tokenId, 10) != null){
                        assetCount_1w--;
                    }

                    maxTokenSize--;
                }
                logger.warn("drop : " + i);

            }
        }catch (Exception e) {
            logger.error("test error", e);
        }
    }

    public static void testTokenBalance() throws CipherException, TronException, IOException, InterruptedException {

//        dropAddressIssues(10, "TFVCqLPSCLUz9pFabt37widUMY7Hzec8UP");

        CompilationResult compilationResult = doCompile("test4_1.sol", true);

        CompilationResult.ContractMetadata dest = compilationResult.getContract("Dest");
        String destAddr = deployContract(bankClient, dest, 1000_000_000);
        logger.info("deploy contract Dst:" + destAddr);
//    cli.transferAsset(destAddr, tokenId, 100000);


        CompilationResult.ContractMetadata src = compilationResult.getContract("Src");
        String srcAddr = deployContract(bankClient, src, 1000_000_000);
        logger.info("deploy contract Src:"+ srcAddr);

    }

    public static void main(String [] args) throws InterruptedException, TronException, CipherException, IOException {

        testTokenBalance();
//        testSuicide();
    }
//    public static void main(String[] args) {
//        String contractFileName = "test01.sol";
//        boolean optimize = true;
//
//        try {
//            WalletFile user = bankClient.generateAddress(testerPswd);
//            cli.loadWalletFile(testerPswd, user);
//            dropBalance(cli, user, 1000_000_000);
//
//            CompilationResult compilationResult = doCompile(contractFileName, optimize);
//
//            ContractMetadata c1 = compilationResult.getContract("GetCode");
//            String contractAddr = deployContract(cli, c1, 1000_000_000);
//            logger.info("deploy contract GetCode:", contractAddr);
//
//            TransactionExtention t1 = (TransactionExtention)triggerContract(cli, contractAddr, "isContract()", null, 0);
//            logger.info(Hex.toHexString(t1.getConstantResult(0).toByteArray()));
//
//        }catch (Exception e){
//            logger.error("test error", e);
//        }
//    }
}

