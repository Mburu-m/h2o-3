package water.rapids;

import hex.quantile.Quantile;
import hex.quantile.QuantileModel;
import water.*;
import water.fvec.*;
import water.util.ArrayUtils;

/** Subclasses take a Frame and produces a scalar.  NAs -> NAs */
abstract class ASTReducerOp extends ASTPrim {
  @Override int nargs() { return -1; }
  @Override
  public Val apply(Env env, Env.StackHelp stk, AST asts[]) {
    // NOTE: no *initial* value needed for the reduction.  Instead, the
    // reduction op is used between pairs of actual values, and never against
    // the empty list.  NaN is returned if there are *no* values in the
    // reduction.
    double d = Double.NaN;
    for( int i=1; i<asts.length; i++ ) {
      Val val = asts[i].exec(env);
      double d2 = val.isFrame() ? new RedOp().doAll(stk.track(val).getFrame().vecs())._d : val.getNum();
      if( i==1 ) d = d2;
      else d = op(d,d2);
    }
    return new ValNum(d);
  }
  /** Override to express a basic math primitive */
  abstract double op( double l, double r );

  class RedOp extends MRTask<RedOp> {
    double _d;
    @Override public void map( Chunks chks ) {
      for( int i = 0; i < chks.numCols(); ++i ) {
        if( _vecs.isNumeric(i) ) throw new IllegalArgumentException("Numeric columns only");
        double sum = _d;
        for( int r = 0; r < chks.numRows(); r++ )
          sum = op(sum, chks.atd(r,i));
        _d = sum;
        if( Double.isNaN(sum) ) break; // Shortcut if the reduction is already NaN
      }
    }
    @Override public void reduce( RedOp s ) { _d = op(_d,s._d); }
  }

//  class NaRmRedOp extends MRTask<NaRmRedOp> {
//    double _d;
//    @Override public void map( Chunk chks[] ) {
//      int rows = chks[0]._len;
//      for( Chunk C : chks ) {
//        if( !C.vec().isNumeric() ) throw new IllegalArgumentException("Numeric columns only");
//        double sum = _d;
//        for( int r = 0; r < rows; r++ ) {
//          double d = C.atd(r);
//          if( !Double.isNaN(d) )
//            sum = op(sum, d);
//        }
//        _d = sum;
//        if( Double.isNaN(sum) ) break; // Shortcut if the reduction is already NaN
//      }
//    }
//    @Override public void reduce( NaRmRedOp s ) { _d = op(_d, s._d); }
//  }

}

/** Optimization for the RollupStats: use them directly */
abstract class ASTRollupOp extends ASTReducerOp {
  @Override
  public String[] args() { return new String[]{"ary"}; }
  abstract double rup( VecAry vec );
  @Override
  public Val apply(Env env, Env.StackHelp stk, AST asts[]) {
    Val arg1 = asts[1].exec(env);
    if( arg1.isRow() ) {        // Row-wise operation
      double[] ds = arg1.getRow();
      double d = ds[0];
      for( int i=1; i<ds.length; i++ )
        d = op(d,ds[i]);
      return new ValRow(new double[]{d}, null);
    }
    // Normal column-wise operation
    Frame fr = stk.track(arg1).getFrame();
    VecAry vecs = fr.vecs();
    if( vecs.len()==0 || vecs.naCnt(0) > 0 ) return new ValNum(Double.NaN);
    double d = rup(vecs.getVecs(0));
    for( int i=1; i<vecs.len(); i++ ) {
      if( vecs.naCnt(i) > 0 ) return new ValNum(Double.NaN);
      d = op(d,rup(vecs.getVecs(i)));
    }
    return new ValNum(d);
  }
}

class ASTSum   extends ASTRollupOp { public String str() { return "sum" ; } double op( double l, double r ) { return          l+r ; } double rup( VecAry vec ) { return vec.mean(0)*vec.numRows(); } }
class ASTMin   extends ASTRollupOp { public String str() { return "min" ; } double op( double l, double r ) { return Math.min(l,r); } double rup( VecAry vec ) { return vec.min(0); } }
class ASTMax   extends ASTRollupOp { public String str() { return "max" ; } double op( double l, double r ) { return Math.max(l,r); } double rup( VecAry vec ) { return vec.max(0); } }

class ASTNACnt extends ASTPrim {
  @Override public String[] args() { return new String[]{"ary"}; }
  @Override public String str() { return "naCnt"; }
  @Override int nargs() { return 1+1; }  // (naCnt fr)
  @Override
  public ValNums apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    double ds[] = new double[fr.numCols()];
    for( int i=0; i<fr.numCols();++i )
      ds[i] = fr.vecs().naCnt(i);
    return new ValNums(ds);
  }
}

class ASTMedian extends ASTPrim {
  @Override public String[] args() { return new String[]{"ary", "method"}; }
  @Override public String str() { return "median"; }
  @Override int nargs() { return 1+2; }  // (median fr method)
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    boolean narm = asts[2].exec(env).getNum()==1;
    if( !narm && (fr.vecs().numRows()==0 || fr.vecs().naCnt(0) > 0) ) return new ValNum(Double.NaN);
    // does linear interpolation for even sample sizes by default
    return new ValNum(median(fr,QuantileModel.CombineMethod.INTERPOLATE));
  }

  static double median(Frame fr, QuantileModel.CombineMethod combine_method) {
    if( fr.numCols() !=1 || !fr.vecs().isNumeric(0) )
      throw new IllegalArgumentException("median only works on a single numeric column");
    // Frame needs a Key for Quantile, might not have one from rapids
    Key tk=null;
    if( fr._key == null ) { DKV.put(tk=Key.make(), fr=new Frame(tk, fr._names,fr.vecs())); }
    // Quantiles to get the median
    QuantileModel.QuantileParameters parms = new QuantileModel.QuantileParameters();
    parms._probs = new double[]{0.5};
    parms._train = fr._key;
    parms._combine_method = combine_method;
    QuantileModel q = new Quantile(parms).trainModel().get();
    double median = q._output._quantiles[0][0];
    q.delete();
    if( tk!=null ) { DKV.remove(tk); }
    return median;
  }

  static double median(Vec v, QuantileModel.CombineMethod combine_method) {
    return median(new Frame(v),combine_method);
  }
}

class ASTMad extends ASTPrim {
  @Override
  public String[] args() { return new String[]{"ary", "combineMethod", "const"}; }
  @Override int nargs() { return 1+3; } //(mad fr combine_method const)
  @Override
  public String str() { return "h2o.mad"; }
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    VecAry vecs = fr.vecs();
    if( vecs.len()==0 || vecs.naCnt(0) > 0 ) return new ValNum(Double.NaN);
    if( vecs.len() > 1 ) throw new IllegalArgumentException("MAD expects a single numeric column");
    QuantileModel.CombineMethod cm = QuantileModel.CombineMethod.valueOf(asts[2].exec(env).getStr().toUpperCase());
    double constant = asts[3].exec(env).getNum();
    return new ValNum(mad(fr,cm,constant));
  }

  static double mad(Frame f, QuantileModel.CombineMethod cm, double constant) {
    // need Frames everywhere because of QuantileModel demanding a Frame...
    Key tk=null;
    if( f._key == null ) { DKV.put(tk = Key.make(), f = new Frame(tk, f._names, f.vecs())); }
    final double median = ASTMedian.median(f,cm);
    Frame abs_dev = new MRTask() {
      @Override public void map(Chunks c, Chunks.AppendableChunks nc) {
        for(int i=0;i<c.numRows();++i)
          nc.addNum(Math.abs(c.at8(i)-median));
      }
    }.doAll(1, Vec.T_NUM, f.vecs()).outputFrame();
    if( abs_dev._key == null ) { DKV.put(tk=Key.make(), abs_dev=new Frame(tk, abs_dev._names,abs_dev.vecs())); }
    double mad = ASTMedian.median(abs_dev,cm);
    DKV.remove(f._key); // drp mapping, keep vec
    DKV.remove(abs_dev._key);
    return constant*mad;
  }
}

// Bulk AND operation on a scalar or numeric column; NAs count as true.  Returns 0 or 1.
class ASTAll extends ASTPrim {
  @Override public String[] args() { return new String[]{"ary"}; }
  @Override public String str() { return "all" ; }
  @Override int nargs() { return 1+1; }
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Val val = stk.track(asts[1].exec(env));
    if( val.isNum() ) return new ValNum(val.getNum()==0?0:1);
    VecAry vecs = val.getFrame().vecs();
    for( int i = 0; i < vecs.len(); ++i)
      if( vecs.nzCnt(i)+vecs.naCnt(i) < vecs.numRows() )
        return new ValNum(0);   // Some zeros in there somewhere
    return new ValNum(1);
  }
}

// Bulk OR operation on boolean column; NAs count as true.  Returns 0 or 1.
class ASTAny extends ASTPrim {
  @Override public String[] args() { return new String[]{"ary"}; }
  @Override int nargs() { return 1+1; } // (any x)
  @Override public String str() { return "any"; }
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Val val = stk.track(asts[1].exec(env));
    if( val.isNum() ) return new ValNum(val.getNum()==0?0:1);
    VecAry vecs = val.getFrame().vecs();
    for( int i = 0; i < vecs.len(); ++i)
      if( vecs.nzCnt(i)+vecs.naCnt(i) > 0 )
        return new ValNum(1);   // Some nonzeros in there somewhere
    return new ValNum(0);
  }
}

// Bulk OR operation on boolean column.  Returns 0 or 1.
class ASTAnyNA extends ASTPrim {
  @Override public String[] args() { return new String[]{"ary"}; }
  @Override int nargs() { return 1+1; } // (any.na x)
  @Override public String str() { return "any.na"; }
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    VecAry vecs = fr.vecs();
    for( int i = 0; i < vecs.len(); ++i)
      if( vecs.nzCnt(i) > 0 ) return new ValNum(1);
    return new ValNum(0);
  }
}


// ----------------------------------------------------------------------------
/** Subclasses take a Frame and produces a scalar.  NAs are dropped */
//abstract class ASTNARedOp extends ASTReducerOp {
//  @Override ValNum apply( Env env, Env.StackHelp stk, AST asts[] ) {
//    Frame fr = stk.track(asts[1].exec(env)).getFrame();
//    return new ValNum(new NaRmRedOp().doAll(fr)._d);
//  }
//}

/** Optimization for the RollupStats: use them directly */
abstract class ASTNARollupOp extends ASTRollupOp {
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    VecAry vecs = fr.vecs();
    if( vecs.len()==0 ) return new ValNum(Double.NaN);
    double d = rup(vecs.getVecs(0));
    for( int i=1; i<vecs.len(); i++ )
      d = op(d,rup(vecs.getVecs(i)));
    return new ValNum(d);
  }
}

class ASTSumNA extends ASTNARollupOp { public String str() { return "sumNA" ; } double op( double l, double r ) { return          l+r ; } double rup( VecAry vec ) { return vec.mean(0)*(vec.numRows() - vec.naCnt(0)); } }
class ASTMinNA extends ASTNARollupOp { public String str() { return "minNA" ; } double op( double l, double r ) { return Math.min(l,r); } double rup( VecAry vec ) { return vec.min(0); } }
class ASTMaxNA extends ASTNARollupOp { public String str() { return "maxNA" ; } double op( double l, double r ) { return Math.max(l,r); } double rup( VecAry vec ) { return vec.max(0); } }

// ----------------------------------------------------------------------------

class ASTMean extends ASTPrim {
  @Override public String[] args() { return new String[]{"ary", "na_rm"}; }
  @Override public String str() { return "mean"; }
  @Override int nargs() { return 1+2; } // (mean X na.rm)
  @Override
  public ValNums apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    boolean narm = asts[2].exec(env).getNum()==1;
    double[] ds = new double[fr.numCols()];
    VecAry vecs = fr.vecs();
    for( int i=0; i<fr.numCols(); i++ )
      ds[i] = (!vecs.isNumeric(i) || vecs.numRows()==0 || (!narm && vecs.naCnt(i) >0)) ? Double.NaN : vecs.mean(i);
    return new ValNums(ds);
  }
}

class ASTSdev extends ASTPrim { // TODO: allow for multiple columns, package result into Frame
  @Override
  public String[] args() { return new String[]{"ary", "na_rm"}; }
  @Override int nargs() { return 1+2; }
  @Override
  public String str() { return "sd"; }
  @Override
  public ValNums apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    boolean narm = asts[2].exec(env).getNum()==1;
    double[] ds = new double[fr.numCols()];
    VecAry vecs = fr.vecs();
    for( int i=0; i<fr.numCols(); i++ )
      ds[i] = (!vecs.isNumeric(i) || vecs.numRows()==0 || (!narm && vecs.naCnt(i) >0)) ? Double.NaN : vecs.sigma(i);
    return new ValNums(ds);
  }
}


class ASTProd extends ASTPrim {
  @Override
  public String[] args() { return new String[]{"ary"}; }
  @Override int nargs() { return 1+1; } // (prod x)
  @Override
  public String str(){ return "prod";}
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    VecAry vecs = fr.vecs();
    for(int i = 0; i < vecs.len(); ++i)
      if (vecs.isCategorical(i) || vecs.isUUID(i) || vecs.isString(i)) throw new IllegalArgumentException("`"+str()+"`" + " only defined on a data frame with all numeric variables");
    double prod=new RedProd().doAll(fr.vecs())._d;
    return new ValNum(prod);
  }

  private static class RedProd extends MRTask<RedProd> {
    double _d;
    @Override public void map( Chunks chks ) {
      for (int i = 0; i < chks.numCols(); i++) {
        double prod=1.;
        for (int r = 0; r < chks.numRows(); r++)
          prod *= chks.atd(r,i);
        _d = prod;
        if( Double.isNaN(prod) ) break;
      }
    }
    @Override public void reduce( RedProd s ) { _d *= s._d; }
  }
}

class ASTProdNA extends ASTPrim {
  @Override
  public String[] args() { return new String[]{"ary"}; }
  @Override int nargs() { return 1+1; } // (prod x)
  @Override
  public String str(){ return "prod.na";}
  @Override
  public ValNum apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    VecAry vecs = fr.vecs();
    for(int i = 0; i < vecs.len(); ++i)
      if (vecs.isCategorical(i) || vecs.isUUID(i) || vecs.isString(i))
        throw new IllegalArgumentException("`"+str()+"`" + " only defined on a data frame with all numeric variables");
    double prod=new RedProd().doAll(fr.vecs())._d;
    return new ValNum(prod);
  }

  private static class RedProd extends MRTask<RedProd> {
    double _d;
    @Override public void map( Chunks chks ) {
      for (int i = 0; i < chks.numCols(); ++i) {
        double prod=1.;
        for (int r = 0; r < chks.numRows(); r++) {
          double d = chks.atd(r,i);
          if( Double.isNaN(d) ) continue;
          prod *= d;
        }
        _d = prod;
        if( Double.isNaN(prod) ) break;
      }
    }
    @Override public void reduce( RedProd s ) { _d += s._d; }
  }
}

abstract class ASTCumu extends ASTPrim {
  @Override
  public String[] args() { return new String[]{"ary"}; }
  @Override int nargs() { return 1+1; } // (cumu x)
  @Override
  public String str() { throw H2O.unimpl(); }
  abstract double op(double l, double r);
  abstract double init();
  @Override
  public ValFrame apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame f = stk.track(asts[1].exec(env)).getFrame();

    if( f.numCols()!=1 ) throw new IllegalArgumentException("Must give a single numeric column.");
    if( !f.vecs().isNumeric(0) ) throw new IllegalArgumentException("Column must be numeric.");

    CumuTask t = new CumuTask(f.vecs().nChunks(),init());
    t.doAll(new byte[]{Vec.T_NUM},f.vecs());
    final double[] chkCumu = t._chkCumu;
    VecAry cumuVec = t.outputFrame().vecs();
    new MRTask() {
      @Override public void map(Chunks c) {
        if( c.cidx()!=0 ) {
          double d=chkCumu[c.cidx()-1];
          for(int i=0;i<c.numRows();++i)
            c.set(i, op(c.atd(i), d));
        }
      }
    }.doAll(cumuVec);
    return new ValFrame(new Frame(cumuVec));
  }

  protected class CumuTask extends MRTask<CumuTask> {
    final int _nchks;   // IN
    final double _init; // IN
    double[] _chkCumu;  // OUT, accumulation over each chunk

    CumuTask(int nchks, double init) { _nchks = nchks; _init=init; }
    @Override public void setupLocal() { _chkCumu = new double[_nchks]; }
    @Override public void map(Chunks c, Chunks.AppendableChunks nc) {
      double acc=_init;
      for(int i=0;i<c.numRows();++i)
        nc.addNum(acc=op(acc,c.atd(i)));
      _chkCumu[c.cidx()]=acc;
    }
    @Override public void reduce(CumuTask t) { if( _chkCumu != t._chkCumu ) ArrayUtils.add(_chkCumu, t._chkCumu); }
    @Override public void postGlobal() {
      for(int i=1;i<_chkCumu.length;++i) _chkCumu[i] = op(_chkCumu[i],_chkCumu[i-1]);
    }
  }
}

class ASTCumSum extends ASTCumu {
  @Override int nargs() { return 1+1; } // (cumsum x)
  @Override
  public String str() { return "cumsum"; }
  @Override double op(double l, double r) { return l+r; }
  @Override double init() { return 0; }
}

class ASTCumProd extends ASTCumu {
  @Override int nargs() { return 1+1; } // (cumprod x)
  @Override
  public String str() { return "cumprod"; }
  @Override double op(double l, double r) { return l*r; }
  @Override double init() { return 1; }
}

class ASTCumMin extends ASTCumu {
  @Override int nargs() { return 1+1; }
  @Override
  public String str() { return "cummin"; }
  @Override double op(double l, double r) { return Math.min(l, r); }
  @Override double init() { return Double.MAX_VALUE; }
}

class ASTCumMax extends ASTCumu {
  @Override int nargs() { return 1+1; }
  @Override
  public String str() { return "cummax"; }
  @Override double op(double l, double r) { return Math.max(l,r); }
  @Override double init() { return -Double.MAX_VALUE; }
}