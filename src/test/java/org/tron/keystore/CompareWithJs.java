package org.tron.keystore;

import static org.tron.common.solc.SolidityCompiler.Options.ABI;
import static org.tron.common.solc.SolidityCompiler.Options.BIN;
import static org.tron.common.solc.SolidityCompiler.Options.HASHES;
import static org.tron.common.solc.SolidityCompiler.Options.INTERFACE;
import static org.tron.common.solc.SolidityCompiler.Options.METADATA;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.filesystem.SolidityFileUtil;
import org.tron.common.solc.CompilationResult;
import org.tron.common.solc.CompilationResult.ContractMetadata;
import org.tron.common.solc.SolidityCompiler;
import org.tron.common.solc.SolidityJsCompiler;

public class CompareWithJs {

  @Test
  public void CompareJs() throws IOException {
    File existFile = SolidityFileUtil.getExistFile("test01.sol");
    SolidityCompiler.Result compilerResult = SolidityCompiler.compile(
        existFile, true, true, ABI, BIN, HASHES,
        INTERFACE,
        METADATA);
    CompilationResult compilationResult = CompilationResult.parse(compilerResult.output);
    SolidityJsCompiler solidityJsCompiler = SolidityJsCompiler.getInstance();
    Map<String, ContractMetadata> compileJs = solidityJsCompiler.compile(existFile, true);
    Map<String, ContractMetadata> compileBin = compilationResult.contracts;
    Assert.assertTrue(equalsForContractMetadataMap(compileJs, compileBin));
  }

  private boolean equalsForContractMetadataMap(Map<String, ContractMetadata> compileJs,
      Map<String, ContractMetadata> compileBin) {
    for (Map.Entry<String, ContractMetadata> entry : compileBin.entrySet()) {
      ContractMetadata contractMetadata = compileBin.get(entry.getKey());
      ContractMetadata contractMetadata1 = entry.getValue();
      System.out.println(contractMetadata.bin);
      System.out.println(contractMetadata1.bin);
      System.out.println(contractMetadata.abi);
      System.out.println(contractMetadata1.abi);
      if (!contractMetadata.bin.equals(contractMetadata1.bin) || !contractMetadata.abi
          .equals(contractMetadata1.abi)) {
        return false;
      }
    }
    return true;
  }
}
/*
2probe
5
42["round_log",16169]
8
*/