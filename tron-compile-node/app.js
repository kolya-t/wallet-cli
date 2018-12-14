var app = require('express')();
var server = require('http').Server(app);
var bodyParser = require('body-parser');
var urlencodedParser = bodyParser.urlencoded({ extended: false })

var solc = require('solc');
solc = solc.setupMethods(require('./soljson_v2.0'));

app.all('/compile', function(req, res, next) {
    res.header("Access-Control-Allow-Origin", "*");
    res.header('Access-Control-Allow-Methods', 'PUT, GET, POST, DELETE, OPTIONS');
    res.header("Access-Control-Allow-Headers", "X-Requested-With");
    res.header('Access-Control-Allow-Headers', 'Content-Type');
    next();
});

server.listen(3009,()=>{
  console.log('listen on 3009');
});

app.get('/',  function (req, res) {
    res.send('123');
});

app.post('/compile',urlencodedParser,async function(req,res){
    try{
        let solidity = req.body.solidity;
        let optimize = req.body.optimize=="true";
        solidity = solidity.replace(/^\s*/,'');
        let compiledContract = solc.compile(solidity,optimize);

        let contractArr=[];

        for (let contractName in compiledContract.contracts) {
            let contractOne={name:contractName,
                bytecode:compiledContract.contracts[contractName].bytecode,
                abi:compiledContract.contracts[contractName].interface}
            contractArr.push(contractOne);
        }
        res.send({code:200,contractArr});
    }catch (e) {
        res.send({code:'error',errMsg:e.toString()})
    }

})




module.exports = app;
