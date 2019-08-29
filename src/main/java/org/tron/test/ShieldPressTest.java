package org.tron.test;


import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import io.netty.util.internal.StringUtil;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.api.GrpcAPI.DecryptNotes;
import org.tron.api.GrpcAPI.DecryptNotesMarked;
import org.tron.api.GrpcAPI.DecryptNotesMarked.NoteTx;
import org.tron.api.GrpcAPI.IvkDecryptAndMarkParameters;
import org.tron.api.GrpcAPI.IvkDecryptParameters;
import org.tron.api.GrpcAPI.Note;
import org.tron.api.GrpcAPI.PrivateParameters;
import org.tron.api.GrpcAPI.ReceiveNote;
import org.tron.api.GrpcAPI.SpendNote;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.TransactionUtils;
import org.tron.core.zen.ShieldAddressInfo;
import org.tron.core.zen.ZenUtils;
import org.tron.core.zen.address.DiversifierT;
import org.tron.core.zen.address.ExpandedSpendingKey;
import org.tron.core.zen.address.FullViewingKey;
import org.tron.core.zen.address.IncomingViewingKey;
import org.tron.core.zen.address.PaymentAddress;
import org.tron.core.zen.address.SpendingKey;
import org.tron.protos.Contract;
import org.tron.protos.Contract.IncrementalMerkleVoucherInfo;
import org.tron.protos.Contract.OutputPoint;
import org.tron.protos.Contract.OutputPointInfo;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.ChainParameters;
import org.tron.protos.Protocol.ChainParameters.ChainParameter;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.walletserver.GrpcClient;

/**
 * 通过RPC接口创建交易，上链，不停的执行；
 * 一个块最多打包一个匿名交易，理论上单线程就可以了，支持多线程
 */
public class ShieldPressTest {
  private static final Logger logger = LoggerFactory.getLogger("ShieldPressTest");

  private  int workType = 1;

  //设置为账户中金额的最大值即可
  private final long AMOUNT = 10000*1000000;
  public static byte[] PRIVATE_KEY;
  private GrpcClient rpcCli;
  private int workThread = 8;
  public final static String OVK = "030c8c2bc59fb3eb8afb047a8ea4b028743d23e7d38c6fa30908358431e2314d";
  private static AtomicLong shieldTransactionCount = new AtomicLong(0);
  //以固定线程数启动
  private ExecutorService fixedThreadPool;

  private String trxIdFile;
  private String fullNodeUrl;

  private boolean initParameters() {
    Config config = Configuration.getByFileName(null,"config_test.conf");

    if (config.hasPath("worktype")) {
      workType = config.getInt("worktype");
    }
    System.out.println("workType: " +  workType );

    String fullNode = "";
    if (config.hasPath("fullnode.ip.list")) {
      fullNode = config.getStringList("fullnode.ip.list").get(0);
    } else {
      System.out.println("Please config fullnode.ip.list");
      return false;
    }
    String solidityNode = "";
    if (config.hasPath("soliditynode.ip.list")) {
      solidityNode = config.getStringList("soliditynode.ip.list").get(0);
    }
    System.out.println("fullNode " + fullNode);
    System.out.println("solidityNode " + solidityNode);
    rpcCli = new GrpcClient(fullNode, solidityNode);

    if (workType == 1 ) {
      if (config.hasPath("priKey")) {
        PRIVATE_KEY = ByteArray.fromHexString(config.getString("priKey"));
      } else {
        System.out.println("Please config priKey");
        return false;
      }

      System.out.println("priKey " + ByteArray.toHexString(PRIVATE_KEY));

      if (config.hasPath("workthread")) {
        workThread = config.getInt("workthread");
      }
      System.out.println("workThread " + workThread);
      fixedThreadPool = Executors.newFixedThreadPool(workThread);
    } else {
      if (config.hasPath("fullhttp")) {
        fullNodeUrl = "http://" + config.getString("fullhttp")+"/wallet/";
      } else {
        System.out.println("Please config fullhttp");
        return false;
      }

      if (config.hasPath("trxidfile")) {
        trxIdFile = config.getString("trxidfile");
      } else {
        System.out.println("Please config trxidfile");
        return false;
      }

      System.out.println("fullhttp " + fullNodeUrl);
      System.out.println("trxidfile " + trxIdFile);
    }

    return true;
  }

  public static Optional<ShieldAddressInfo> getNewShieldedAddress() {
    ShieldAddressInfo addressInfo = new ShieldAddressInfo();
    try {
      DiversifierT diversifier = new DiversifierT().random();
      SpendingKey spendingKey = SpendingKey.random();

      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      PaymentAddress paymentAddress = incomingViewingKey.address(diversifier).get();

      addressInfo.setSk(spendingKey.getValue());
      addressInfo.setD(diversifier);
      addressInfo.setIvk(incomingViewingKey.getValue());
      addressInfo.setOvk(fullViewingKey.getOvk());
      addressInfo.setPkD(paymentAddress.getPkD());

      if (addressInfo.validateCheck()) {
        return Optional.of(addressInfo);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return Optional.empty();
  }

  public static boolean checkTransactionOnline(final String trxId, final String ivk, GrpcClient rpcCli) {
    while (true) {
      //如果可以扫描到交易，则继续
      if (scanBlockByIvk(trxId, ivk, rpcCli)) {
//      if (getTransactionInfoById(trxId)) {
        return true;
      }
      try {
        Thread.sleep(500);
      } catch (Exception e) {
        break;
      }
    }
    return false;
  }


  public static long getShieldFee(GrpcClient client) {
    Optional<ChainParameters> chainParameters = client.getChainParameters();
    if (chainParameters.isPresent()) {
      for (ChainParameter para : chainParameters.get().getChainParameterList()) {
        if (para.getKey().equals("getShieldedTransactionFee")) {
          return para.getValue();
        }
      }
    }
    return 10000000L;
  }

  public static boolean scanBlockByIvk(final String hash, final String ivk, GrpcClient rpcCli) {
    Block block = rpcCli.getBlock(-1);
    if (block != null) {
      long blockNum = block.getBlockHeader().toBuilder().getRawData().getNumber();
      {
        long start = ((blockNum - 1000) >= 0) ? (blockNum - 1000) : 0;
        long end = blockNum;

        IvkDecryptParameters.Builder builder = IvkDecryptParameters.newBuilder();
        builder.setStartBlockIndex(start);
        builder.setEndBlockIndex(end);
        builder.setIvk(ByteString.copyFrom(ByteArray.fromHexString(ivk)));
        DecryptNotes notes = rpcCli.scanNoteByIvk(builder.build());
        if (notes != null) {
          for (int i = 0; i < notes.getNoteTxsList().size(); ++i) {
            DecryptNotes.NoteTx noteTx = notes.getNoteTxsList().get(i);
            if (hash.equals(ByteArray.toHexString(noteTx.getTxid().toByteArray()))) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private boolean getTransactionInfoById(final String hash) {
    Optional<TransactionInfo> transactionInfo = rpcCli.getTransactionInfoById(hash);
    if (transactionInfo.isPresent() && transactionInfo.get().getBlockNumber() != 0L) {
      System.out.println("TrxId " + hash + " is in block " + transactionInfo.get().getBlockNumber());
      return true;
    }
    return false;
  }

  private long getTransactionBlockNum(final String hash) {
    Optional<TransactionInfo> transactionInfo = rpcCli.getTransactionInfoById(hash);
    if (transactionInfo.isPresent() && transactionInfo.get().getBlockNumber() != 0L) {
      //System.out.println("TrxId " + hash + " is in block " + transactionInfo.get().getBlockNumber());
      return transactionInfo.get().getBlockNumber();
    }
    return 0;
  }

  public SpendNote getUnspendNote(final ShieldAddressInfo shieldAddress ) {
    try {
      Block block = rpcCli.getBlock(-1);
      if (block == null) {
        System.out.println("getBlock error");
      }

      long blockNum = block.getBlockHeader().toBuilder().getRawData().getNumber();
      long start = ((blockNum - 1000) >= 0) ? (blockNum - 1000) : 0;
      long end = blockNum;

      IvkDecryptAndMarkParameters.Builder builder = IvkDecryptAndMarkParameters.newBuilder();
      builder.setStartBlockIndex(start);
      builder.setEndBlockIndex(end);
      builder.setIvk(ByteString.copyFrom(shieldAddress.getIvk()));
      builder.setAk(ByteString.copyFrom(shieldAddress.getFullViewingKey().getAk()));
      builder.setNk(ByteString.copyFrom(shieldAddress.getFullViewingKey().getNk()));

      DecryptNotesMarked decryptNotes = rpcCli.scanAndMarkNoteByIvk(builder.build());
      if (decryptNotes.getNoteTxsCount() > 0) {
        for (NoteTx noteTx : decryptNotes.getNoteTxsList()) {
          //没有被花掉，且序号等于目标序号
          if (!noteTx.getIsSpend()) {
            //获取默克尔树相关信息
            OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
            OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
            outPointBuild.setHash(ByteString.copyFrom(noteTx.getTxid().toByteArray()));
            outPointBuild.setIndex(noteTx.getIndex());
            request.addOutPoints(outPointBuild.build());

            IncrementalMerkleVoucherInfo merkleVoucherInfo = rpcCli
                .GetMerkleTreeVoucherInfo(request.build());
            if (merkleVoucherInfo == null) {
              System.out.println("Can't get all merkel tree, please check the notes.");
              break;
            }

            Note.Builder noteBuild = Note.newBuilder();
            noteBuild.setPaymentAddress(noteTx.getNote().getPaymentAddress());
            noteBuild.setValue(noteTx.getNote().getValue());
            noteBuild.setRcm(noteTx.getNote().getRcm());
            noteBuild.setMemo(noteTx.getNote().getMemo());

            SpendNote.Builder spendNoteBuilder = SpendNote.newBuilder();
            spendNoteBuilder.setNote(noteBuild.build());
            spendNoteBuilder.setAlpha(rpcCli.getRcm().getValue());
            spendNoteBuilder.setVoucher(merkleVoucherInfo.getVouchers(0));
            spendNoteBuilder.setPath(merkleVoucherInfo.getPaths(0));

            return spendNoteBuilder.build();
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return SpendNote.getDefaultInstance();
  }

  /**
   * 创建公开地址转匿名地址的交易，并上链
   * @param shieldAddress  目标匿名地址信息
   * @return 上链的交易ID
   */
  private String generatePublicToShieldOnlineTransaction(final ShieldAddressInfo shieldAddress ) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(AMOUNT);

      byte[] ovk = ByteArray.fromHexString(OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        Note.Builder noteBuild = Note.newBuilder();
        noteBuild.setPaymentAddress(shieldAddress.getAddress());
        noteBuild.setValue(AMOUNT-fee);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      Transaction transaction = transactionExtention.getTransaction();

      Any any = transaction.getRawData().getContract(0).getParameter();
      Contract.ShieldedTransferContract shieldedTransferContract =
          any.unpack(Contract.ShieldedTransferContract.class);
      if (shieldedTransferContract.getFromAmount() > 0) {
        transaction = TransactionUtils.sign(transaction, ecKey);
      }

      logger.info("TrxId {} fromAddress {} toAddress {}",
          ByteArray.toHexString(transactionExtention.getTxid().toByteArray()),
          ByteArray.toHexString(fromAddress),
          shieldAddress.getAddress());

      rpcCli.broadcastTransaction(transaction);
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  /**
   * 创建匿名到匿名的交易
   * @param fromShieldAddress  源匿名地址
   * @param toShieldAddress   目标匿名地址
   * @return 交易的ID
   */
  private String generatShieldToShieldOnlineTransaction(final ShieldAddressInfo fromShieldAddress,
      final ShieldAddressInfo toShieldAddress) {

    long fee = getShieldFee(rpcCli);
    long shieldFromAmount = 0L;
    try {
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();

      SpendingKey spendingKey = new SpendingKey(fromShieldAddress.getSk());
      ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKey.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));

      //扫描获取未花费的note
      Block block = rpcCli.getBlock(-1);
      if (block == null) {
        System.out.println("getBlock error");
        return "";
      }
      long blockNum = block.getBlockHeader().toBuilder().getRawData().getNumber();
      long start = ((blockNum - 1000) >= 0) ? (blockNum - 1000) : 0;
      IvkDecryptAndMarkParameters.Builder ivkBuilder = IvkDecryptAndMarkParameters.newBuilder();
      ivkBuilder.setStartBlockIndex(start);
      ivkBuilder.setEndBlockIndex(blockNum);
      ivkBuilder.setIvk(ByteString.copyFrom(fromShieldAddress.getIvk()));
      ivkBuilder.setAk(ByteString.copyFrom(fromShieldAddress.getFullViewingKey().getAk()));
      ivkBuilder.setNk(ByteString.copyFrom(fromShieldAddress.getFullViewingKey().getNk()));

      DecryptNotesMarked decryptNotes = rpcCli.scanAndMarkNoteByIvk(ivkBuilder.build());
      if (decryptNotes.getNoteTxsCount() > 0) {
        NoteTx noteTx = decryptNotes.getNoteTxs(0);
        //没有被花掉，且序号等于目标序号
        if (!noteTx.getIsSpend()) {
          //获取默克尔树相关信息
          OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
          OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
          outPointBuild.setHash(ByteString.copyFrom(noteTx.getTxid().toByteArray()));
          outPointBuild.setIndex(noteTx.getIndex());
          request.addOutPoints(outPointBuild.build());

          shieldFromAmount = noteTx.getNote().getValue();
          if (shieldFromAmount < fee ) {
            System.out.println("The value " + shieldFromAmount+ " can't afford the fee " + fee );
            return "";
          }

          IncrementalMerkleVoucherInfo merkleVoucherInfo = rpcCli
              .GetMerkleTreeVoucherInfo(request.build());
          if (merkleVoucherInfo == null) {
            System.out.println("Can't get all merkel tree, please check the notes.");
            return "";
          }

          Note.Builder noteBuild = Note.newBuilder();
          noteBuild.setPaymentAddress(noteTx.getNote().getPaymentAddress());
          noteBuild.setValue(noteTx.getNote().getValue());
          noteBuild.setRcm(noteTx.getNote().getRcm());
          noteBuild.setMemo(noteTx.getNote().getMemo());

          SpendNote.Builder spendNoteBuilder = SpendNote.newBuilder();
          spendNoteBuilder.setNote(noteBuild.build());
          spendNoteBuilder.setAlpha(rpcCli.getRcm().getValue());
          spendNoteBuilder.setVoucher(merkleVoucherInfo.getVouchers(0));
          spendNoteBuilder.setPath(merkleVoucherInfo.getPaths(0));

          builder.addShieldedSpends(spendNoteBuilder.build());
        } else {
          System.out.println("Can't find unspend note. something is wrong!");
          return "";
        }
      } else {
        System.out.println("Can't find unspend note. something is wrong! 2");
        return "";
      }

      {
        Note.Builder noteBuild = Note.newBuilder();
        noteBuild.setPaymentAddress(toShieldAddress.getAddress());
        noteBuild.setValue(shieldFromAmount-fee);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("press test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());

      logger.info("TrxId {} fromAddress {} toAddress {}",
          ByteArray.toHexString(transactionExtention.getTxid().toByteArray()),
          fromShieldAddress.getAddress(),
          toShieldAddress.getAddress());

      rpcCli.broadcastTransaction(transactionExtention.getTransaction());
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  boolean init() {
    if (!initParameters()) {
      System.out.println("Init parameters failure.");
      return false;
    }
    return true;
  }

  void rpcPress() {
    //随机等待后启动线程
    sleepRadom(workThread*3/2 );
    System.out.println("Rpc Thread " + Thread.currentThread().getName() + " start to work");

    while (true) {
      //公开转匿名
      ShieldAddressInfo fromShieldAddress = getNewShieldedAddress().get();
      String hash = generatePublicToShieldOnlineTransaction(fromShieldAddress);

      while (true) {
        //如果可以扫描到交易，则继续
        if (!checkTransactionOnline(hash, ByteArray.toHexString(fromShieldAddress.getIvk()), rpcCli)) {
          System.out.println("Can't find transaction hash " + hash + " on line.");
          break;
        }
        shieldTransactionCount.incrementAndGet();

        long blockNum = getTransactionBlockNum(hash);
        logger.info("Transaction {} is in blcok {}", hash, blockNum);
//        System.out.println("transaction hash is " + hash + " online.");
        ShieldAddressInfo toShieldAddress = getNewShieldedAddress().get();
        hash = generatShieldToShieldOnlineTransaction(fromShieldAddress, toShieldAddress);
        if (StringUtil.isNullOrEmpty(hash)) {
          break;
        }

        fromShieldAddress = toShieldAddress;
      }
    }
  }


  void rpcFailurePress() {
    System.out.println("Rpc Failure Thread " + Thread.currentThread().getName() + " start to work");
    while (true) {
      //公开转匿名
      FailureZKTransaction testClass = new FailureZKTransaction();
      Random random = new Random();
      int i = random.nextInt(20)%20 + 1;
      String methodName = "FailureTest" + i;

      try {
         testClass.getClass().
            getMethod(methodName, new Class[]{GrpcClient.class}).invoke(null, new Object[]{rpcCli});
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }


  void sleepRadom(int n) {
    Random random = new Random();
    int s = random.nextInt(n);
    threadSleep(s*1000);
  }

  void threadSleep(long millisecond) {
    try {
      Thread.sleep(millisecond);
    } catch (Exception e) {
    }
  }




//  private final int[] BLOCK_INDEX = {0, 100, 300, 500, 700, 900};

  private final int[] BLOCK_INDEX = {0, 5,10,11,13,18};

  void checkMerkerPath() {
    List<String> trxIdList = ZenUtils.getListFromFile(trxIdFile);

    boolean fileExist = false;
    final String resultFileName = "cm.result";
    File file = new File(resultFileName);
    if (file.exists()) {
      fileExist = true;
    }
    List<String> stringList = ZenUtils.getListFromFile(resultFileName);

    for (int i = 0; i < trxIdList.size(); i++) {
      String trxId = trxIdList.get(i);
      OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
      OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
      outPointBuild.setHash(ByteString.copyFrom(ByteArray.fromHexString(trxId)));
      outPointBuild.setIndex(0);
      request.addOutPoints(outPointBuild.build());
      for (int j = 0; j < BLOCK_INDEX.length; j++) {
        request.setBlockNum(BLOCK_INDEX[j]);

        IncrementalMerkleVoucherInfo merkleVoucherInfo = rpcCli
            .GetMerkleTreeVoucherInfo(request.build());
        String result = JsonFormat.printToString(merkleVoucherInfo, false);

        if (!fileExist) {
          ZenUtils.appendToFileTail(resultFileName, result);
          continue;
        }

        if (!stringList.get(i * BLOCK_INDEX.length + j).equals(result)) {
          System.out.println("!! TRXID: " + trxId + "(" + BLOCK_INDEX[j] + ") not equals !!");
          System.out.println("Target is: " + stringList.get(i));
          System.out.println("Result is: " + result);
        }
      }
    }
  }



  /**
   * 发送 post请求访问本地应用并根据传递参数不同返回不同结果
   */
  public static boolean post(final String url, final JSONObject requestBody) {
    boolean bRet = false;
    // 创建默认的httpClient实例.
    CloseableHttpClient httpclient = HttpClients.createDefault();
    // 创建httppost
    HttpPost httppost = new HttpPost(url);
    httppost.setHeader("Content-type", "application/json; charset=utf-8");
    httppost.setHeader("Connection", "Close");
    // 创建参数队列
    if (requestBody != null) {
      StringEntity entity = new StringEntity(requestBody.toString(), Charset.forName("UTF-8"));
      entity.setContentEncoding("UTF-8");
      entity.setContentType("application/json");
      httppost.setEntity(entity);
    }

    try {
      CloseableHttpResponse response = httpclient.execute(httppost);
      try {
        bRet = verificationResult(response);
      } finally {
        response.close();
      }
    } catch (ClientProtocolException e) {
      e.printStackTrace();
    } catch (UnsupportedEncodingException e1) {
      e1.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      // 关闭连接,释放资源
      try {
        httpclient.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return bRet;
  }

  public static JSONObject parseResponseContent(HttpResponse response) {
    try {
      String result = EntityUtils.toString(response.getEntity());
      StringEntity entity = new StringEntity(result, Charset.forName("UTF-8"));
      System.out.print("Response content: " + EntityUtils.toString(entity, "UTF-8"));
//      response.setEntity(entity);
//      JSONObject obj = JSONObject.parseObject(result);
//      return obj;
      return new JSONObject();
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * constructor.
   */
  public static Boolean verificationResult(HttpResponse response) {
    if (response.getStatusLine().getStatusCode() != 200) {
      return false;
    }

    parseResponseContent(response);
//    JSONObject responseContent = parseResponseContent(response);
//    if (responseContent.containsKey("raw_data") &&
//        !StringUtil.isNullOrEmpty(responseContent.getString("raw_data"))) {
//      return true;
//    }
    return false;
  }

  void startWork() {
    if (workType == 1) {
      //为异常case准备有效的两个cm
      FailureZKTransaction.prepareUnspentNote(rpcCli);

      //匿名交易压测模式
      for (int i = 0; i < workThread; i++) {
        fixedThreadPool.execute(new Runnable() {
          @Override
          public void run() {
            rpcPress();
          }
        });
      }

      //固定启动两个异常Case的线程
      for (int i = 0; i < 2; i++) {
        Executors.newFixedThreadPool(2).execute(new Runnable() {
          @Override
          public void run() {
            rpcFailurePress();
          }
        });
      }

      while (true) {
        System.out.println("-->  " + new DateTime(System.currentTimeMillis())
            + " Transaction num: " + shieldTransactionCount.get());
        try {
          Thread.sleep(30000);
        } catch (Exception e) {
        }
      }
    } else {  //监控模式
      DateTime dateTime = new DateTime(System.currentTimeMillis());
      String fileName = dateTime.toString();

      checkMerkerPath();

      post(fullNodeUrl+"getmerker", new JSONObject());
      System.out.println(fileName + "  Deal finished!!");
    }
  }

  public static void main(String[] args) {
    ShieldPressTest test = new ShieldPressTest();
    if (!test.init()) {
      System.out.println("init failure");
      return;
    }
    test.startWork();
  }
}
