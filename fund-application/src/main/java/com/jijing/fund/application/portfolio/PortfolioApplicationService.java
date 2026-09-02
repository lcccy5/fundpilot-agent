package com.jijing.fund.application.portfolio;

import com.jijing.fund.analytics.portfolio.*;
import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.portfolio.*;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class PortfolioApplicationService implements PortfolioUseCase {
    private static final int MAX_IMPORT_BYTES=5*1024*1024;
    private static final int MAX_IMPORT_ROWS=2000;
    private final PortfolioRepository portfolios;private final FundNavRepository navs;private final PortfolioPositionProjector projector;
    private final MoneyWeightedReturnCalculator xirr;private final TimeWeightedReturnCalculator twr;private final PortfolioConcentrationCalculator concentration;
    private final List<SpreadsheetTableReader> readers;private final ObjectMapper json;private final Clock clock;
    public PortfolioApplicationService(PortfolioRepository portfolios,FundNavRepository navs,PortfolioPositionProjector projector,
            MoneyWeightedReturnCalculator xirr,TimeWeightedReturnCalculator twr,PortfolioConcentrationCalculator concentration,
            List<SpreadsheetTableReader> readers,ObjectMapper json,Clock clock){
        this.portfolios=portfolios;this.navs=navs;this.projector=projector;this.xirr=xirr;this.twr=twr;this.concentration=concentration;
        this.readers=readers==null?List.of(new CsvSpreadsheetTableReader()):List.copyOf(readers);this.json=json==null?new ObjectMapper():json;this.clock=clock;
    }
    @Override public List<UserPortfolio> list(AuthenticatedUser actor){return portfolios.findByOwner(actor.userId());}
    @Override @Transactional public UserPortfolio create(AuthenticatedUser actor,String name){String display=required(name);Instant now=clock.instant();var p=new UserPortfolio(PortfolioId.random(),actor.userId(),display,"CNY",PortfolioStatus.ACTIVE,0,now,now);portfolios.savePortfolio(p);return p;}
    @Override @Transactional public FundTransaction append(AuthenticatedUser actor,PortfolioId id,TransactionCommand c){
        var portfolio=owned(actor,id);if(portfolio.status()!=PortfolioStatus.ACTIVE)throw new PortfolioException("portfolio is archived");validate(c);
        var duplicate=portfolios.findByIdempotency(actor.userId(),id,c.idempotencyKey());if(duplicate.isPresent())return duplicate.get();
        var tx=new FundTransaction(UUID.randomUUID().toString(),id,actor.userId(),new FundCode(c.fundCode()),c.type(),c.tradeDate(),c.confirmDate(),c.shares(),c.grossAmount(),c.fee(),c.confirmedNav(),"CNY","MANUAL",c.idempotencyKey(),c.reversesTransactionId(),clock.instant());
        persist(actor,portfolio,List.of(tx));return tx;
    }
    @Override @Transactional public FundTransaction reverse(AuthenticatedUser actor,PortfolioId id,String transactionId,String idempotencyKey){
        if(idempotencyKey==null||idempotencyKey.isBlank())throw new PortfolioException("idempotencyKey is required");
        var duplicate=portfolios.findByIdempotency(actor.userId(),id,idempotencyKey);if(duplicate.isPresent())return duplicate.get();
        var original=portfolios.findTransaction(actor.userId(),id,transactionId).orElseThrow(()->new PortfolioNotFoundException("transaction not found"));
        if(original.transactionType()==TransactionType.REVERSAL)throw new PortfolioException("cannot reverse a reversal");
        if(portfolios.hasReversal(actor.userId(),original.transactionId()))throw new PortfolioConflictException("transaction already reversed");
        var compensating=compensatingType(original.transactionType());
        var tx=new FundTransaction(UUID.randomUUID().toString(),id,actor.userId(),original.fundCode(),compensating,original.tradeDate(),original.confirmDate(),original.shares(),original.grossAmount(),original.fee(),original.confirmedNav(),"CNY","REVERSAL",idempotencyKey,original.transactionId(),clock.instant());
        persist(actor,owned(actor,id),List.of(tx));return tx;
    }
    @Override public List<FundTransaction> transactions(AuthenticatedUser actor,PortfolioId id){owned(actor,id);return portfolios.findTransactions(id,actor.userId());}
    @Override public List<FundPosition> positions(AuthenticatedUser actor,PortfolioId id){owned(actor,id);return projector.project(portfolios.findTransactions(id,actor.userId()));}
    @Override @Transactional public SnapshotRebuildResult rebuildSnapshots(AuthenticatedUser actor,PortfolioId id){
        var portfolio=owned(actor,id);portfolios.deletePositionSnapshots(id,actor.userId());
        var positions=projector.project(portfolios.findTransactions(id,actor.userId()));
        String hash=sha(positions.toString());portfolios.replacePositionSnapshots(id,actor.userId(),LocalDate.now(clock),positions,hash);
        return new SnapshotRebuildResult(id,hash,positions.size(),portfolios.countPositionSnapshots(id,actor.userId()));
    }
    @Override public PortfolioValuation valuation(AuthenticatedUser actor,PortfolioId id,LocalDate asOf){
        owned(actor,id);LocalDate date=asOf==null?LocalDate.now(clock):asOf;var values=new ArrayList<PortfolioValuation.PositionValue>();var warnings=new ArrayList<String>();BigDecimal cost=BigDecimal.ZERO,value=BigDecimal.ZERO;boolean incomplete=false;
        for(FundPosition p:positions(actor,id)){cost=cost.add(p.remainingCost());var nav=navs.findHistory(p.fundCode(),LocalDate.of(1990,1,1),date).stream().filter(n->!n.navDate().isAfter(date)).max(Comparator.comparing(NavPoint::navDate));if(nav.isEmpty()){incomplete=true;warnings.add("NAV_UNAVAILABLE:"+p.fundCode().value());values.add(new PortfolioValuation.PositionValue(p,null,null,null,"UNAVAILABLE"));continue;}BigDecimal v=p.confirmedShares().multiply(nav.get().unitNav()).setScale(4,RoundingMode.HALF_UP);value=value.add(v);values.add(new PortfolioValuation.PositionValue(p,nav.get().unitNav(),nav.get().navDate(),v,nav.get().navDate().equals(date)?"AVAILABLE":"STALE_NAV"));}
        return new PortfolioValuation(id,date,cost,incomplete?null:value,incomplete?null:value.subtract(cost),List.copyOf(values),List.copyOf(warnings),"portfolio-position-v1");
    }
    @Override public PortfolioReturn returns(AuthenticatedUser actor,PortfolioId id,LocalDate asOf){
        LocalDate date=asOf==null?LocalDate.now(clock):asOf;var valuation=valuation(actor,id,date);var warnings=new ArrayList<>(valuation.warnings());
        if(valuation.totalValue()==null)return new PortfolioReturn(id,date,null,null,"DATA_NOT_READY",List.of(),List.copyOf(warnings),"portfolio-return-v1");
        var txs=portfolios.findTransactions(id,actor.userId()).stream().filter(tx->!tx.confirmDate().isAfter(date)).toList();
        var flows=new ArrayList<MoneyWeightedReturnCalculator.CashFlow>();
        for(var tx:txs){BigDecimal amount=cashFlowAmount(tx);if(amount.signum()!=0)flows.add(new MoneyWeightedReturnCalculator.CashFlow(tx.confirmDate(),amount));}
        if(valuation.totalValue().signum()!=0)flows.add(new MoneyWeightedReturnCalculator.CashFlow(date,valuation.totalValue()));
        var money=xirr.calculate(flows);if(money.isEmpty())warnings.add("XIRR_UNAVAILABLE");
        var time=twr.calculate(subPeriods(valuation,txs,date));if(time.isEmpty())warnings.add("TWR_UNAVAILABLE");
        return new PortfolioReturn(id,date,money.orElse(null),time.orElse(null),money.isPresent()?"AVAILABLE":"UNAVAILABLE",List.copyOf(flows),List.copyOf(warnings),"portfolio-return-v1");
    }
    @Override public PortfolioRiskView risk(AuthenticatedUser actor,PortfolioId id,LocalDate asOf){
        var valuation=valuation(actor,id,asOf);
        var result=concentration.calculate(valuation.positions().stream().map(PortfolioValuation.PositionValue::value).toList());
        String coverage=valuation.warnings().isEmpty()?"COMPLETE":"PARTIAL";
        return new PortfolioRiskView(id,valuation.asOfDate(),result.maxFundWeight(),result.top3Weight(),result.hhi(),result.status(),coverage,valuation.warnings(),"portfolio-risk-v1",null,null);
    }
    @Override @Transactional public ImportBatch previewImport(AuthenticatedUser actor,PortfolioId id,String fileName,byte[] content){
        var portfolio=owned(actor,id);if(content==null||content.length==0||content.length>MAX_IMPORT_BYTES)throw new PortfolioException("import file must be 1 byte to 5 MB");
        if(fileName==null||!(fileName.toLowerCase(Locale.ROOT).endsWith(".csv")||fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")))throw new PortfolioException("only csv and xlsx are supported");
        var table=reader(fileName,content).read(fileName,content);
        if(table.size()>MAX_IMPORT_ROWS)throw new PortfolioException("import cannot exceed 2000 rows");
        var rows=new ArrayList<ImportRow>();int valid=0;
        for(int i=0;i<table.size();i++){
            var parsed=parseRow(table.get(i),i+2);
            if(parsed.errorCode()!=null)rows.add(parsed);
            else {valid++;rows.add(parsed);}
        }
        Instant now=clock.instant();
        var batch=new ImportBatch(UUID.randomUUID().toString(),id,actor.userId(),sha(content),fileName,ImportBatchStatus.PREVIEWED,table.size(),valid,table.size()-valid,now,null,rows);
        portfolios.saveImportBatch(batch);return batch;
    }
    @Override public ImportBatch importBatch(AuthenticatedUser actor,PortfolioId id,String batchId){return portfolios.findImportBatch(actor.userId(),id,batchId).orElseThrow(()->new PortfolioNotFoundException("import batch not found"));}
    @Override @Transactional public ImportBatch commitImport(AuthenticatedUser actor,PortfolioId id,String batchId,String fileSha256){
        var batch=importBatch(actor,id,batchId);
        if(batch.status()!=ImportBatchStatus.PREVIEWED)throw new PortfolioConflictException("import batch is not previewed");
        if(fileSha256!=null&&!fileSha256.isBlank()&&!fileSha256.equalsIgnoreCase(batch.fileSha256()))throw new PortfolioConflictException("file hash mismatch");
        var portfolio=owned(actor,id);
        var txs=new ArrayList<FundTransaction>();
        for(ImportRow row:batch.rows()){
            if(row.errorCode()!=null)continue;
            var command=commandFromRow(row);
            var existing=portfolios.findByIdempotency(actor.userId(),id,command.idempotencyKey());
            if(existing.isEmpty()){
                txs.add(new FundTransaction(UUID.randomUUID().toString(),id,actor.userId(),new FundCode(command.fundCode()),command.type(),command.tradeDate(),command.confirmDate(),command.shares(),command.grossAmount(),command.fee(),command.confirmedNav(),"CNY","IMPORT",command.idempotencyKey(),null,clock.instant()));
            }
        }
        persist(actor,portfolio,txs);portfolios.markImportCommitted(batchId);
        return importBatch(actor,id,batchId);
    }
    @Override @Transactional public void deleteImport(AuthenticatedUser actor,PortfolioId id,String batchId){
        var batch=importBatch(actor,id,batchId);if(batch.status()==ImportBatchStatus.COMMITTED)throw new PortfolioConflictException("committed import cannot be deleted");
        portfolios.deleteImportBatch(batchId);
    }
    private void persist(AuthenticatedUser actor,UserPortfolio portfolio,List<FundTransaction> txs){
        List<FundTransaction> candidate=new ArrayList<>(portfolios.findTransactions(portfolio.portfolioId(),actor.userId()));candidate.addAll(txs);projector.project(candidate);
        if(!portfolios.updateVersion(portfolio.portfolioId(),actor.userId(),portfolio.version(),portfolio.version()+1))throw new PortfolioConflictException("portfolio was updated concurrently");
        txs.forEach(portfolios::appendTransaction);
        String hash=sha(candidate.stream().map(FundTransaction::transactionId).toList().toString());
        portfolios.replacePositionSnapshots(portfolio.portfolioId(),actor.userId(),LocalDate.now(clock),projector.project(candidate),hash);
    }
    private UserPortfolio owned(AuthenticatedUser a,PortfolioId id){return portfolios.findByIdAndOwner(id,a.userId()).orElseThrow(()->new PortfolioNotFoundException("portfolio not found"));}
    private SpreadsheetTableReader reader(String fileName,byte[] content){return readers.stream().filter(r->r.supports(fileName,content)).findFirst().orElseThrow(()->new PortfolioException("unsupported import file"));}
    private ImportRow parseRow(Map<String,String> raw,int sourceRow){
        try{
            String type=first(raw,"交易类型","transactionType");
            String fund=first(raw,"基金代码","fundCode");
            String trade=first(raw,"交易日期","tradeDate");
            String confirm=first(raw,"确认日期","confirmDate");
            if(fund==null||!fund.matches("\\d{6}"))return err(sourceRow,raw,"INVALID_FUND_CODE","fund code must be 6 digits");
            TransactionType parsed=parseType(type);if(parsed==null)return err(sourceRow,raw,"INVALID_TYPE","unknown transaction type");
            LocalDate tradeDate=LocalDate.parse(trade);LocalDate confirmDate=LocalDate.parse(confirm);
            if(confirmDate.isBefore(tradeDate))return err(sourceRow,raw,"INVALID_DATE","confirmDate cannot precede tradeDate");
            return new ImportRow(sourceRow,json.writeValueAsString(raw),null,null);
        }catch(Exception e){return err(sourceRow,raw,"INVALID_ROW","row cannot be parsed");}
    }
    private TransactionCommand commandFromRow(ImportRow row){
        try{
            @SuppressWarnings("unchecked") Map<String,String> raw=json.readValue(row.rawJson(),Map.class);
            String external=first(raw,"外部流水号","externalReference");
            String idempotency=external==null||external.isBlank()?"import-"+row.sourceRowNumber()+"-"+sha(row.rawJson()).substring(0,12):external;
            return new TransactionCommand(first(raw,"基金代码","fundCode"),parseType(first(raw,"交易类型","transactionType")),
                    LocalDate.parse(first(raw,"交易日期","tradeDate")),LocalDate.parse(first(raw,"确认日期","confirmDate")),
                    decimal(first(raw,"确认份额","shares")),decimal(first(raw,"交易金额","grossAmount")),decimal(first(raw,"手续费","fee")),
                    decimal(first(raw,"确认净值","confirmedNav")),idempotency,first(raw,"备注","note"),null);
        }catch(Exception e){throw new PortfolioException("committed row is invalid");}
    }
    private List<TimeWeightedReturnCalculator.SubPeriod> subPeriods(PortfolioValuation valuation,List<FundTransaction> txs,LocalDate date){
        if(valuation.totalValue()==null||txs.isEmpty())return List.of();
        return List.of(new TimeWeightedReturnCalculator.SubPeriod(txs.getFirst().confirmDate(),date,valuation.totalCost(),valuation.totalValue(),BigDecimal.ZERO));
    }
    private static BigDecimal cashFlowAmount(FundTransaction tx){
        return switch(tx.transactionType()){
            case SUBSCRIPTION, CONVERSION_IN -> tx.grossAmount().add(tx.fee()).negate();
            case REDEMPTION, CONVERSION_OUT -> tx.grossAmount().subtract(tx.fee());
            case CASH_DIVIDEND -> tx.grossAmount();
            case FEE_ADJUSTMENT -> tx.fee().negate();
            case DIVIDEND_REINVESTMENT, REVERSAL -> BigDecimal.ZERO;
        };
    }
    private static TransactionType compensatingType(TransactionType type){
        return switch(type){case SUBSCRIPTION,DIVIDEND_REINVESTMENT,CONVERSION_IN -> TransactionType.REDEMPTION;case REDEMPTION,CONVERSION_OUT -> TransactionType.SUBSCRIPTION;case CASH_DIVIDEND,FEE_ADJUSTMENT -> TransactionType.FEE_ADJUSTMENT;case REVERSAL -> TransactionType.REVERSAL;};
    }
    private static TransactionType parseType(String raw){
        if(raw==null)return null;String v=raw.trim().toUpperCase(Locale.ROOT);
        return switch(v){case "申购","SUBSCRIPTION"->TransactionType.SUBSCRIPTION;case "赎回","REDEMPTION"->TransactionType.REDEMPTION;case "现金分红","CASH_DIVIDEND"->TransactionType.CASH_DIVIDEND;case "红利再投资","DIVIDEND_REINVESTMENT"->TransactionType.DIVIDEND_REINVESTMENT;case "转换转出","CONVERSION_OUT"->TransactionType.CONVERSION_OUT;case "转换转入","CONVERSION_IN"->TransactionType.CONVERSION_IN;case "冲正","REVERSAL"->TransactionType.REVERSAL;case "手续费调整","FEE_ADJUSTMENT"->TransactionType.FEE_ADJUSTMENT;default -> {try{yield TransactionType.valueOf(v);}catch(Exception e){yield null;}}};
    }
    private ImportRow err(int row,Map<String,String> raw,String code,String message){try{return new ImportRow(row,json.writeValueAsString(raw),code,message);}catch(Exception e){return new ImportRow(row,"{}",code,message);}}
    private static String first(Map<String,String> raw,String... keys){for(String key:keys){for(var e:raw.entrySet())if(e.getKey()!=null&&e.getKey().trim().equalsIgnoreCase(key)&&e.getValue()!=null&&!e.getValue().isBlank())return e.getValue().trim();}return null;}
    private static BigDecimal decimal(String v){return v==null||v.isBlank()?BigDecimal.ZERO:new BigDecimal(v);}
    private static void validate(TransactionCommand c){if(c==null||c.type()==null||c.tradeDate()==null||c.confirmDate()==null||c.idempotencyKey()==null||c.idempotencyKey().isBlank())throw new PortfolioException("transaction fields are required");if(c.confirmDate().isBefore(c.tradeDate()))throw new PortfolioException("confirmDate cannot precede tradeDate");if(c.type()==TransactionType.REVERSAL&&(c.reversesTransactionId()==null||c.reversesTransactionId().isBlank()))throw new PortfolioException("reversal must reference original transaction");}
    private static String required(String n){if(n==null||n.trim().isBlank()||n.trim().length()>80)throw new PortfolioException("portfolio name is invalid");return n.trim();}
    private static String sha(byte[] content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String sha(String value){return sha(value.getBytes(StandardCharsets.UTF_8));}
}
