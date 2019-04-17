package org.tron.keystore;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.solc.CompilationResult;
import org.tron.common.utils.Utils;
import org.tron.core.exception.CipherException;
import org.tron.core.exception.TronException;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.script.AutoDeployer;
import org.tron.walletserver.WalletApi;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;

public class SolidityTest extends AutoDeployer {


  @Ignore
  @Test
  public void tryTest(){

    try {
      WalletFile user = bankClient.generateAddress(testerPswd);
      cli.loadWalletFile(testerPswd, user);
      dropBalance(cli, user, 1000_000_000);

      CompilationResult compilationResult = doCompile("test01.sol", true);

      CompilationResult.ContractMetadata c1 = compilationResult.getContract("GetCode");
      String contractAddr = deployContract(cli, c1, 1000_000_000);
      logger.info("deploy contract GetCode:", contractAddr);

      TransactionExtention t1 = (TransactionExtention)triggerContract(cli, contractAddr, "isContract()", null, 0);
      logger.info(Hex.toHexString(t1.getConstantResult(0).toByteArray()));
      long l = bytesToLong(t1.getConstantResult(0).toByteArray());
      Assert.assertTrue(l == 0);

    }catch (Exception e) {
      logger.error("test error", e);
    }
  }

//  @Test
//  public void testTokenBalance() throws CipherException, TronException, IOException, InterruptedException {
//
//    WalletFile user = bankClient.generateAddress(testerPswd);
//    dropAddressIssues(100, user.getAddress());
//
//    dropBalance(cli, user, 10_000_000_000L);
//
//    CompilationResult compilationResult = doCompile("test4_1.sol", true);
//
//    CompilationResult.ContractMetadata dest = compilationResult.getContract("Dest");
//    String destAddr = deployContract(cli, dest, 1000_000_000);
//    logger.info("deploy contract Dst:", destAddr);
////    cli.transferAsset(destAddr, tokenId, 100000);
//
//
//    CompilationResult.ContractMetadata src = compilationResult.getContract("Src");
//    String srcAddr = deployContract(cli, src, 1000_000_000);
//    logger.info("deploy contract Src:", srcAddr);
//
//  }

//  @Ignore
//  @Test
//  public void testSuicide(){
//    try {
//      int maxTokenSize = 10001;
//
//      Calendar calendar = Calendar.getInstance();
//      calendar.setTime(new Date());
//      calendar.add(Calendar.DAY_OF_MONTH, +1);
//      long dayBefore = calendar.getTime().getTime();
//
//      calendar.add(Calendar.YEAR, +1);
//      long yearAfter = calendar.getTime().getTime();
//
//      // 编译合约
//      CompilationResult compilationResult = doCompile("test01.sol", true);
//      CompilationResult.ContractMetadata c1 = compilationResult.getContract("GetCode");
//
//      // 有1000个 asset 的账号
//      WalletFile userContract = bankClient.generateAddress(testerPswd);
//      cli.loadWalletFile(testerPswd, userContract);
//      dropBalance(cli, userContract, 10_000_000L);
//
//      // 有1k 个 asset 的 contract
//      int assetCount_1k = 1000;
//      String contract_1k = deployContract(cli, c1, 1000_000_000);
//      logger.info("deploy contract 1k:", contract_1k);
//
//      // 有2k 个 asset 的 contract
//      int assetCount_2k = 2000;
//      String contract_2k = deployContract(cli, c1, 1000_000_000);
//      logger.info("deploy contract 2k:", contract_2k);
//
//      // 有5k 个 asset 的 contract
//      int assetCount_5k = 5000;
//      String contract_5k = deployContract(cli, c1, 1000_000_000);
//      logger.info("deploy contract 5k:", contract_5k);
//
//      // 有7k 个 asset 的 contract
//      int assetCount_7k = 7000;
//      String contract_7k = deployContract(cli, c1, 1000_000_000);
//      logger.info("deploy contract 7k:", contract_7k);
//
//      // 有1w 个 asset 的 contract
//      int assetCount_1w = 10_000;
//      String contract_1w = deployContract(cli, c1, 1000_000_000);
//      logger.info("deploy contract 1w:", contract_1w);
//
//
//      int i = 0;
//      while (maxTokenSize >= 0){
//        ++i;
//        WalletFile user = bankClient.generateAddress(testerPswd);
//        cli.loadWalletFile(testerPswd, user);
//        dropBalance(cli, user, 10_000_000_000L);
//        String txId = cli.assetIssue("assert_" + i, 100000,
//            1000, 100000, 0,
//            dayBefore, yearAfter, 0, "desc_" + i, String.format("http://abc_%d.com.", i),
//            1000, 10000, null);
//        TransactionInfo info = checkSignedTxId(txId);
//        if (info != null){
//          Account account = cli.queryAccount();
//          String tokenId = account.getAssetIssuedID().toStringUtf8();
//
//          if (assetCount_1k > 0 && cli.transferAsset(contract_1k, tokenId, 10) != null){
//            assetCount_1k--;
//          }
//
//          if (assetCount_2k > 0 && cli.transferAsset(contract_2k, tokenId, 10) != null){
//            assetCount_2k--;
//          }
//
//          if (assetCount_5k > 0 && cli.transferAsset(contract_5k, tokenId, 10) != null){
//            assetCount_5k--;
//          }
//
//          if (assetCount_7k > 0 && cli.transferAsset(contract_7k, tokenId, 10) != null){
//            assetCount_7k--;
//          }
//
//          if (assetCount_1w > 0 && cli.transferAsset(contract_1w, tokenId, 10) != null){
//            assetCount_1w--;
//          }
//
//          maxTokenSize--;
//        }
//
//      }
//    }catch (Exception e) {
//      logger.error("test error", e);
//    }
//  }
}