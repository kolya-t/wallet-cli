package org.tron.common.solc;

import com.google.common.collect.Maps;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.tron.common.filesystem.SolidityFileUtil;
import org.tron.common.solc.CompilationResult.ContractMetadata;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;

//@Component
public class SolidityJsCompiler {

  private Solc solc;

  private static SolidityJsCompiler INSTANCE;

  //    @Autowired
  public SolidityJsCompiler() {
    new Thread(() -> SolidityJsCompiler.this.initJsCompiler()).start();
    try {
      Thread.sleep(10000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    solc = new Solc();
  }

  private void initJsCompiler() {
    System.out.println(System.getProperty("user.dir"));
    try {
      Process p = null;
      String line = null;
      BufferedReader stdout = null;
      String jsPath = System.getProperty("user.dir") + "/tron-compile-node/" + "app.js";
      String command = "node " + jsPath;
      p = Runtime.getRuntime().exec(command);
      stdout = new BufferedReader(new InputStreamReader(
          p.getInputStream()));
      while ((line = stdout.readLine()) != null) {
        System.out.println(line);
      }
      stdout.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static String readCityFile(File file02) {
    FileInputStream is = null;
    StringBuilder stringBuilder = null;
    try {
      if (file02.length() != 0) {

        is = new FileInputStream(file02);
        InputStreamReader streamReader = new InputStreamReader(is);
        BufferedReader reader = new BufferedReader(streamReader);
        String line;
        stringBuilder = new StringBuilder();
        while ((line = reader.readLine()) != null) {
          // stringBuilder.append(line);
          stringBuilder.append(line).append("\n");
        }
        reader.close();
        is.close();
      } else {
        System.out.println("file lengeth is 0");
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
    return String.valueOf(stringBuilder);

  }

  public Map<String, ContractMetadata> compile(File existFile, boolean optimize)
      throws IOException {
    JSONObject jsonResult = compileSrc(existFile, optimize);
    JSONArray contractArr = jsonResult.getJSONArray("contractArr");
    Map<String, ContractMetadata> comtracts = Maps.newHashMap();
    for (int i = 0; i < contractArr.length(); i++) {
      JSONObject contract = (JSONObject) contractArr.get(i);
      ContractMetadata contractMetadata = new ContractMetadata();

      contractMetadata.abi = contract.getString("abi");
      contractMetadata.bin = contract.getString("bytecode");
      comtracts.put(contract.getString("name"), contractMetadata);
    }
    return comtracts;
  }

  public JSONObject compileSrc(File existFile, boolean optimize) throws IOException {
    OkHttpClient client = new OkHttpClient();
    String readCityFile = readCityFile(existFile);
    String encode = URLEncoder.encode(readCityFile, "UTF-8");
    MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
    RequestBody body = RequestBody.create(mediaType,
        "solidity=" + encode + "&optimize=" + optimize);
    Request request = new Request.Builder()
        .url("http://127.0.0.1:3009/compile")
        .post(body)
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .addHeader("Cache-Control", "no-cache")
        .addHeader("Postman-Token", "44c898c1-61c3-4477-b21a-fbb52f9f11f9")
        .build();

    Response response = client.newCall(request).execute();
    //System.out.println(response.body().string());
    JSONObject myJsonObject = new JSONObject(response.body().string());
    return myJsonObject;
  }

  public static SolidityJsCompiler getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new SolidityJsCompiler();
    }
    return INSTANCE;
  }

  public static void main(String[] args) {
    // Object compile = compile(true);
    // System.out.println(compile);
    SolidityJsCompiler solidityJsCompiler = SolidityJsCompiler.getInstance();

    File existFile = SolidityFileUtil.getExistFile("test01.sol");
    try {
      Map<String, ContractMetadata> compile = solidityJsCompiler.compile(existFile, true);
      compile.forEach((k, v) -> {
        System.out.println(k);
        System.out.println(v.abi);
        System.out.println(v.bin);
      });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}