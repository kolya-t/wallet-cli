package org.tron.test;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.PrivateParameters;
import org.tron.api.GrpcAPI.ReceiveNote;
import org.tron.api.GrpcAPI.Return;
import org.tron.api.GrpcAPI.SpendNote;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.TransactionUtils;
import org.tron.core.zen.ShieldAddressInfo;
import org.tron.core.zen.address.ExpandedSpendingKey;
import org.tron.core.zen.address.SpendingKey;
import org.tron.protos.Contract;
import org.tron.protos.Contract.IncrementalMerkleVoucherInfo;
import org.tron.protos.Contract.OutputPoint;
import org.tron.protos.Contract.OutputPointInfo;
import org.tron.protos.Protocol.Transaction;
import org.tron.walletserver.GrpcClient;
import org.tron.walletserver.WalletApi;

public class FailureZKTransaction {
  private static final Logger logger = LoggerFactory.getLogger("FailureZKTransaction");
//  private static final String OWNER_ADDRESS = "TXtrbmfwZ2LxtoCveEhZT86fTss1w8rwJE";
//  private static final String OWNER_PRIVATE_KEY =
//      "0528dc17428585fc4dece68b79fa7912270a1fe8e85f244372f59eb7e8925e04";
//
//  private static final String TO_ADDRESS = "TRGhNNfnmgLegT4zHNjEqDSADjgmnHvubJ";
//  private static final String TO_PRIVATE_KEY =
//      "7f7f701e94d4f1dd60ee5205e7ea8ee31121427210417b608a6b2e96433549a7";
//
//  private static final long SHIELD_FEE = 10_000_000L;
  private static final String TO_ADDRESS = "TEtV4s8gczUf6vAk5rpLX4nniLdFPWrmmA";
  private static final String TO_PRIVATE_KEY =
      "68e52dd77a8b6941cff922779c8d8171631161bf3487c8f4e7b950840d15787a";
  public static long TRANSFER_AMOUNT = 100L;

  private static List<ShieldAddressInfo> unspentShieldedAddressArray = new ArrayList<>();
  private static List<SpendNote> unSpendNoteArray = new ArrayList<>();
  private static ShieldAddressInfo spendShieldedAddress;
  private static SpendNote.Builder spendNoteBuilder;

  public static boolean prepareUnspentNote(GrpcClient rpcCli) {
    try {
      //公开地址转到一个匿名地址中，创建一个被花费的note
      {
        final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
        byte[] fromAddress = ecKey.getAddress();
        long fee = ShieldPressTest.getShieldFee(rpcCli);

        PrivateParameters.Builder builder = PrivateParameters.newBuilder();
        builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
        builder.setFromAmount(2 * TRANSFER_AMOUNT + fee * 2);
        byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
        builder.setOvk(ByteString.copyFrom(ovk));
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        {
          ShieldAddressInfo ShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
          noteBuild.setPaymentAddress(ShieldAddress.getAddress());
          noteBuild.setValue(2 * TRANSFER_AMOUNT + fee);
          noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
          noteBuild.setMemo(ByteString.copyFrom("testSpend".getBytes()));
          builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
          spendShieldedAddress = ShieldAddress;
        }
        TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
        Transaction transaction = transactionExtention.getTransaction();
        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);

        String hashSpent = ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
        if (!ShieldPressTest
            .checkTransactionOnline(hashSpent, ByteArray.toHexString(spendShieldedAddress.getIvk()),
                rpcCli)) {
          System.out.println("Can't find transaction hash " + hashSpent + " on line.");
          return false;
        }
        OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
        OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
        outPointBuild.setHash(ByteString.copyFrom(ByteArray.fromHexString(hashSpent)));
        outPointBuild.setIndex(0);
        request.addOutPoints(outPointBuild.build());

        IncrementalMerkleVoucherInfo merkleVoucherInfo = rpcCli
            .GetMerkleTreeVoucherInfo(request.build());
        if (merkleVoucherInfo.getPathsCount() < 1) {
          System.out.println("Can't get all merkel tree, please check the notes.");
          return false;
        }
        spendNoteBuilder = SpendNote.newBuilder();
        spendNoteBuilder.setNote(noteBuild.build());
        spendNoteBuilder.setAlpha(rpcCli.getRcm().getValue());
        spendNoteBuilder.setVoucher(merkleVoucherInfo.getVouchers(0));
        spendNoteBuilder.setPath(merkleVoucherInfo.getPaths(0));
      }

      //匿名地址转到两个匿名地址中，消费一个note，产生两个未花费的note
      {
        PrivateParameters.Builder builderTwo = PrivateParameters.newBuilder();
        SpendingKey spendingKey = new SpendingKey(spendShieldedAddress.getSk());
        ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();
        builderTwo.setAsk(ByteString.copyFrom(expandedSpendingKey.getAsk()));
        builderTwo.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
        builderTwo.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));
        builderTwo.addShieldedSpends(spendNoteBuilder.build());
        GrpcAPI.Note.Builder noteBuildA = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo ShieldAddressA = ShieldPressTest.getNewShieldedAddress().get();
        {
          unspentShieldedAddressArray.add(ShieldAddressA);
          noteBuildA.setPaymentAddress(ShieldAddressA.getAddress());
          noteBuildA.setValue(TRANSFER_AMOUNT);
          noteBuildA.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
          noteBuildA.setMemo(ByteString.copyFrom("testA".getBytes()));
          builderTwo
              .addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuildA.build()).build());
        }
        GrpcAPI.Note.Builder noteBuildB = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo ShieldAddressB = ShieldPressTest.getNewShieldedAddress().get();
        {
          unspentShieldedAddressArray.add(ShieldAddressB);
          noteBuildB.setPaymentAddress(ShieldAddressB.getAddress());
          noteBuildB.setValue(TRANSFER_AMOUNT);
          noteBuildB.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
          noteBuildB.setMemo(ByteString.copyFrom("testB".getBytes()));
          builderTwo
              .addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuildB.build()).build());
        }
        TransactionExtention transactionExtention = rpcCli
            .createShieldTransaction(builderTwo.build());
        rpcCli.broadcastTransaction(transactionExtention.getTransaction());

        String hash = ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
        if (!ShieldPressTest
            .checkTransactionOnline(hash, ByteArray.toHexString(ShieldAddressA.getIvk()),
                rpcCli)) {
          System.out.println("Can't find transaction hash " + hash + " on line.");
          return false;
        }
        OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
        for (int i = 0; i < 2; i++) {
          OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
          outPointBuild.setHash(ByteString.copyFrom(ByteArray.fromHexString(hash)));
          outPointBuild.setIndex(0);
          request.addOutPoints(outPointBuild.build());
        }
        IncrementalMerkleVoucherInfo merkleVoucherInfo = rpcCli
            .GetMerkleTreeVoucherInfo(request.build());
        if (merkleVoucherInfo.getPathsCount() != 2) {
          System.out.println("Can't get all merkel tree, please check the notes.");
          return false;
        }

        //保存两个spentNote，做匿名转出时使用，不会被花掉
        SpendNote.Builder spendNoteBuilder = SpendNote.newBuilder();
        spendNoteBuilder.setNote(noteBuildA.build());
        spendNoteBuilder.setAlpha(rpcCli.getRcm().getValue());
        spendNoteBuilder.setVoucher(merkleVoucherInfo.getVouchers(0));
        spendNoteBuilder.setPath(merkleVoucherInfo.getPaths(0));
        unSpendNoteArray.add(spendNoteBuilder.build());

        spendNoteBuilder = SpendNote.newBuilder();
        spendNoteBuilder.setNote(noteBuildB.build());
        spendNoteBuilder.setAlpha(rpcCli.getRcm().getValue());
        spendNoteBuilder.setVoucher(merkleVoucherInfo.getVouchers(1));
        spendNoteBuilder.setPath(merkleVoucherInfo.getPaths(1));
        unSpendNoteArray.add(spendNoteBuilder.build());
      }
      return true;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }

  //仅公开地址转公开地址
  public static String FailureTest1(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      builder.setTransparentToAddress(ByteString.copyFrom(WalletApi.decodeFromBase58Check(TO_ADDRESS)));
      builder.setToAmount(TRANSFER_AMOUNT);
      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();

        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }

        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址A转匿名地址，但用公开地址B的私钥签名
  public static String FailureTest2(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
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
        final ECKey otherEcKey = ECKey.fromPrivate(ByteArray.fromHexString(TO_PRIVATE_KEY));
        transaction = TransactionUtils.sign(transaction, otherEcKey);
      }

      rpcCli.broadcastTransaction(transaction);
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址A转匿名地址，转出公开地址不签名
  public static String FailureTest3(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }
      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      Transaction transaction = transactionExtention.getTransaction();

      rpcCli.broadcastTransaction(transaction);
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址转匿名地址，转出金额 - 转入金额 < fee
  public static String FailureTest4(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee-1);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
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

      rpcCli.broadcastTransaction(transaction);
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址转匿名地址，转出金额大于账户实际余额
  public static String FailureTest5(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(Long.MAX_VALUE);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(Long.MAX_VALUE-fee);
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

      rpcCli.broadcastTransaction(transaction);
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址转匿名地址，转出公开账户不存在
  public static String FailureTest6(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ByteArray.fromHexString(TO_PRIVATE_KEY));
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
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

      rpcCli.broadcastTransaction(transaction);
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址转匿名地址，转出金额为0
  public static String FailureTest7(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(0L);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(-fee);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();

        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }

        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址转匿名地址，转出金额为负数
  public static String FailureTest8(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(-TRANSFER_AMOUNT);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(-TRANSFER_AMOUNT-fee);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();

        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址转匿名地址，公开地址非法
  public static String FailureTest9(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom("aaaaa".getBytes()));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
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

      rpcCli.broadcastTransaction(transaction);
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //匿名地址转公开地址，公开地址非法
  public static String FailureTest10(GrpcClient rpcCli) {
    long fee = ShieldPressTest.getShieldFee(rpcCli);
    try {
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();

      //note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKey = new SpendingKey(unspentShieldedAddressArray.get(0).getSk());
      ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKey.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));
      builder.addShieldedSpends(unSpendNoteArray.get(0));

      builder.setTransparentToAddress(ByteString.copyFrom("bbbbbbb".getBytes()));
      builder.setToAmount(TRANSFER_AMOUNT-fee);
      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //匿名地址转公开地址，公开地址转入金额为0
  public static String FailureTest11(GrpcClient rpcCli) {

    long fee = ShieldPressTest.getShieldFee(rpcCli);
    try {
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();

      //note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKey = new SpendingKey(unspentShieldedAddressArray.get(0).getSk());
      ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKey.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));
      builder.addShieldedSpends(unSpendNoteArray.get(0));

      builder.setTransparentToAddress(ByteString.copyFrom(WalletApi.decodeFromBase58Check(TO_ADDRESS)));
      builder.setToAmount(0);
      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //匿名地址转公开地址，公开地址转入金额为负数
  public static String FailureTest12(GrpcClient rpcCli) {

    long fee = ShieldPressTest.getShieldFee(rpcCli);
    try {
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();

      //note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKey = new SpendingKey(unspentShieldedAddressArray.get(0).getSk());
      ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKey.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));
      builder.addShieldedSpends(unSpendNoteArray.get(0));

      builder.setTransparentToAddress(ByteString.copyFrom(WalletApi.decodeFromBase58Check(TO_ADDRESS)));
      builder.setToAmount(-fee);
      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址+匿名地址 转出到公开地址
  public static String FailureTest13(GrpcClient rpcCli) {
    final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
    byte[] fromAddress = ecKey.getAddress();
    long fee = ShieldPressTest.getShieldFee(rpcCli);
    try {
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();

      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);

      //note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKey = new SpendingKey(unspentShieldedAddressArray.get(0).getSk());
      ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKey.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));
      builder.addShieldedSpends(unSpendNoteArray.get(0));

      builder.setTransparentToAddress(ByteString.copyFrom(WalletApi.decodeFromBase58Check(TO_ADDRESS)));
      builder.setToAmount(TRANSFER_AMOUNT*2);
      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //匿名地址转公开，匿名的个数超过2个
  public static String FailureTest14(GrpcClient rpcCli) {
    final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
    byte[] fromAddress = ecKey.getAddress();
    long fee = ShieldPressTest.getShieldFee(rpcCli);
    try {
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();

      //note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKeyA = new SpendingKey(unspentShieldedAddressArray.get(0).getSk());
      ExpandedSpendingKey expandedSpendingKeyA = spendingKeyA.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKeyA.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKeyA.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKeyA.getOvk()));
      builder.addShieldedSpends(unSpendNoteArray.get(0));

      //note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKeyB = new SpendingKey(unspentShieldedAddressArray.get(1).getSk());
      ExpandedSpendingKey expandedSpendingKeyB = spendingKeyB.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKeyB.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKeyB.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKeyB.getOvk()));
      builder.addShieldedSpends(unSpendNoteArray.get(1));

      builder.setTransparentToAddress(ByteString.copyFrom(WalletApi.decodeFromBase58Check(TO_ADDRESS)));
      builder.setToAmount(TRANSFER_AMOUNT*2-fee);
      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开转匿名，匿名的note个数超过2个
  public static String FailureTest15(GrpcClient rpcCli) {

    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(3*TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      for (int i=0; i<3; i++) {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();

        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //匿名地址转出，无目标账户
  public static String FailureTest16(GrpcClient rpcCli) {
    try {
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();

      //note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKeyA = new SpendingKey(unspentShieldedAddressArray.get(0).getSk());
      ExpandedSpendingKey expandedSpendingKeyA = spendingKeyA.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKeyA.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKeyA.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKeyA.getOvk()));
      builder.addShieldedSpends(unSpendNoteArray.get(0));

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址转匿名，但不设置公开地址
  public static String FailureTest17(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //匿名转匿名，其中匿名的note已经被花掉
  public static String FailureTest18(GrpcClient rpcCli) {
    try {
      long fee = ShieldPressTest.getShieldFee(rpcCli);
      //公开输入
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      //匿名输入note 的value为 TRANSFER_AMOUNT
      SpendingKey spendingKeyA = new SpendingKey(spendShieldedAddress.getSk());
      ExpandedSpendingKey expandedSpendingKeyA = spendingKeyA.expandedSpendingKey();
      builder.setAsk(ByteString.copyFrom(expandedSpendingKeyA.getAsk()));
      builder.setNsk(ByteString.copyFrom(expandedSpendingKeyA.getNsk()));
      builder.setOvk(ByteString.copyFrom(expandedSpendingKeyA.getOvk()));
      builder.addShieldedSpends(spendNoteBuilder.build());
      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(2*TRANSFER_AMOUNT-fee);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开地址A转匿名地址+公开地址B，但不设置B的公开地址
  public static String FailureTest19(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);
      //公开输入
      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(2*TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        //匿名输出
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(TRANSFER_AMOUNT);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }
      //公开输出
      builder.setToAmount(TRANSFER_AMOUNT);

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //公开转匿名，其中匿名的note中金额存在为负数的情况，满足金额平衡
  public static String FailureTest20(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(2*TRANSFER_AMOUNT);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(-TRANSFER_AMOUNT);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  //匿名地址转公开，匿名note中有1个note的金额为0，剩余一个为有效值，不满足金额平衡
  public static String FailureTest21(GrpcClient rpcCli) {
    try {
      final ECKey ecKey = ECKey.fromPrivate(ShieldPressTest.PRIVATE_KEY);
      byte[] fromAddress = ecKey.getAddress();
      long fee = ShieldPressTest.getShieldFee(rpcCli);

      PrivateParameters.Builder builder = PrivateParameters.newBuilder();
      builder.setTransparentFromAddress(ByteString.copyFrom(fromAddress));
      builder.setFromAmount(TRANSFER_AMOUNT+fee);
      byte[] ovk = ByteArray.fromHexString(ShieldPressTest.OVK);
      builder.setOvk(ByteString.copyFrom(ovk));

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(0);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test1".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      {
        GrpcAPI.Note.Builder noteBuild = GrpcAPI.Note.newBuilder();
        ShieldAddressInfo fromShieldAddress = ShieldPressTest.getNewShieldedAddress().get();
        noteBuild.setPaymentAddress(fromShieldAddress.getAddress());
        noteBuild.setValue(2*TRANSFER_AMOUNT);
        noteBuild.setRcm(ByteString.copyFrom(rpcCli.getRcm().getValue().toByteArray()));
        noteBuild.setMemo(ByteString.copyFrom("test2".getBytes()));
        builder.addShieldedReceives(ReceiveNote.newBuilder().setNote(noteBuild.build()).build());
      }

      TransactionExtention transactionExtention = rpcCli.createShieldTransaction(builder.build());
      if (transactionExtention.hasTransaction()) {
        Transaction transaction = transactionExtention.getTransaction();
        Any any = transaction.getRawData().getContract(0).getParameter();
        Contract.ShieldedTransferContract shieldedTransferContract =
            any.unpack(Contract.ShieldedTransferContract.class);
        if (shieldedTransferContract.getFromAmount() > 0) {
          transaction = TransactionUtils.sign(transaction, ecKey);
        }
        rpcCli.broadcastTransaction(transaction);
        return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
      } else if (transactionExtention.hasResult()) {
        Return resultReturn = transactionExtention.getResult();
        System.out.println(resultReturn.getCode() +":"+ new String(resultReturn.getMessage().toByteArray()));
      }
      return ByteArray.toHexString(transactionExtention.getTxid().toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }
}
