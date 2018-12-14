package org.tron.script;

import static org.tron.common.solc.SolidityCompiler.Options.ABI;
import static org.tron.common.solc.SolidityCompiler.Options.BIN;
import static org.tron.common.solc.SolidityCompiler.Options.HASHES;
import static org.tron.common.solc.SolidityCompiler.Options.INTERFACE;
import static org.tron.common.solc.SolidityCompiler.Options.METADATA;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.filesystem.SolidityFileUtil;
import org.tron.common.solc.CompilationResult;
import org.tron.common.solc.CompilationResult.ContractMetadata;
import org.tron.common.solc.SolidityCompiler;
import org.tron.common.utils.AbiUtil;
import org.tron.core.exception.CipherException;
import org.tron.core.exception.TronException;
import org.tron.keystore.WalletFile;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.walletserver.AutoClient;
import org.tron.walletserver.WalletApi;


public class AutoCompiler {

  protected static final Logger logger = LoggerFactory.getLogger(AutoCompiler.class);
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

  protected static Long dropBalance(WalletFile user, long needBalance)
      throws TronException, InterruptedException {

    Account account = cli.queryAccount();
    Long balance = account.getBalance();
    if (needBalance <= balance) {
      return balance;
    }

    String bankTxId = bankClient.sendCoin(user.getAddress(), needBalance - balance);
    if (bankTxId != null && bankTxId.length() > 0) {
      Thread.sleep(3_000);
      account = cli.queryAccount();
      balance = account.getBalance();
    }

    if (needBalance > balance) {
      throw new TronException(
          "address: " + user.getAddress() + " balance: " + balance + " need :" + needBalance);
    }
    return balance;
  }

  protected static String deployContract(ContractMetadata contract, int feeLimit)
      throws TronException {
    String txId = cli
        .deployContract("GetCode", contract.abi, contract.bin, feeLimit, 0, 0, 10000, 0, "", null);
    Optional<TransactionInfo> result = cli.getTransactionInfoById(txId);
    if (result.isPresent() && result.get().getResult().equals(TransactionInfo.code.SUCESS)) {
      logger.info("deployContract trx: " + txId);

      TransactionInfo transactionInfo = result.get();
      return WalletApi.encode58Check(transactionInfo.getContractAddress().toByteArray());
    } else {
      throw new TronException("deployContract error");
    }
  }

  protected static Object triggerContract(String contractAddress, String funcSign,
      List<Object> params, long callValue) throws TronException {

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

  protected static CompilationResult doCompile(String contractFileName, boolean optimize)
      throws IOException {
    try {
      SolidityCompiler.Result compilerResult = SolidityCompiler.compile(
          SolidityFileUtil.getExistFile(contractFileName), optimize, true, ABI, BIN, HASHES,
          INTERFACE,
          METADATA);
      return CompilationResult.parse(compilerResult.output);
    } catch (IOException e) {
      logger.error("test error", e);
      throw e;
    }
  }

  protected static void tryTest(String contractFileName, boolean optimize)
      throws CipherException, TronException {

    try {
      WalletFile user = bankClient.generateAddress(testerPswd);
      cli.loadWalletFile(testerPswd, user);
      dropBalance(user, 1000_000_000);

      CompilationResult compilationResult = doCompile(contractFileName, optimize);

      ContractMetadata c1 = compilationResult.getContract("GetCode");
      String contractAddr = deployContract(c1, 1000_000_000);
      logger.info("deploy contract GetCode:", contractAddr);

      TransactionExtention t1 = (TransactionExtention) triggerContract(contractAddr, "isContract()",
          null, 0);
      logger.info(Hex.toHexString(t1.getConstantResult(0).toByteArray()));

    } catch (Exception e) {
      logger.error("test error", e);
    }
  }

  public static long bytesToLong(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
    buffer.put(bytes, 0, bytes.length);
    buffer.flip();//need flip
    return buffer.getLong();
  }

  public static void main(String[] args) {
    try {
      tryTest("test01.sol", true);
    } catch (TronException | CipherException e) {
      logger.error("main exist", e);
    }
  }

}

