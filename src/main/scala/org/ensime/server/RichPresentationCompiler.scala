package org.ensime.server
import org.ensime.config.ProjectConfig
import org.ensime.model._
import scala.actors._
import scala.actors.Actor._
import scala.collection.mutable.{ LinkedHashMap }
import scala.tools.nsc.{ Settings, FatalError }
import scala.tools.nsc.interactive.{ Global, CompilerControl }
import scala.tools.nsc.reporters.{ Reporter }
import scala.tools.nsc.symtab.{ Flags, Types }
import scala.tools.nsc.util.{ SourceFile, Position }
import java.io.File

trait RichCompilerControl extends CompilerControl with RefactoringInterface { self: RichPresentationCompiler =>

  def askOr[A](op: => A, handle: Throwable => A): A = {
    val result = new Response[A]()
    scheduler postWorkItem new WorkItem {
      def apply() = respond(result)(op)
    }
    result.get.fold(o => o, handle)
  }

  def askSymbolInfoAt(p: Position): Option[SymbolInfo] = askOr(
    symbolAt(p).fold(s => Some(SymbolInfo(s)), t => None), t => None)

  def askTypeInfoAt(p: Position): Option[TypeInfo] = askOr(
    typeAt(p).fold(s => Some(TypeInfo(s)), t => None), t => None)

  def askTypeInfoById(id: Int): Option[TypeInfo] = askOr(
    typeById(id) match {
      case Some(t) => Some(TypeInfo(t))
      case None => None
    }, t => None)

  def askTypeInfoByName(name: String): Option[TypeInfo] = askOr(
    typeByName(name) match {
      case Some(t) => Some(TypeInfo(t))
      case None => None
    }, t => None)

  def askCallCompletionInfoById(id: Int): Option[CallCompletionInfo] = askOr(
    typeById(id) match {
      case Some(t: Type) => Some(CallCompletionInfo(t))
      case _ => None
    }, t => None)

  def askPackageByPath(path: String): Option[PackageInfo] = askOr(
    Some(PackageInfo.fromPath(path)), t => None)

  def askQuickReloadFile(f: SourceFile) {
    askOr(reloadSources(List(f)), t => ())
  }

  def askReloadFile(f: SourceFile) {
    askReloadFiles(List(f))
  }

  def askReloadFiles(files: Iterable[SourceFile]) = askOr({
    val x = new Response[Unit]()
    reload(files.toList, x)
    x.get
  }, t => ())

  def askReloadAllFiles() = {
    val all = ((config.sourceFilenames.map(getSourceFile(_))) ++ firsts).toSet.toList
    askReloadFiles(all)
  }

  def askInspectTypeById(id: Int): Option[TypeInspectInfo] = askOr(
    typeById(id) match {
      case Some(t: Type) => Some(inspectType(t))
      case _ => None
    }, t => None)

  def askInspectTypeAt(p: Position): Option[TypeInspectInfo] = askOr({
    reloadSources(List(p.source))
    Some(inspectTypeAt(p))
  }, t => None)

  def askCompletePackageMember(path: String, prefix: String): Iterable[PackageMemberInfoLight] = askOr({
    completePackageMember(path, prefix)
  }, t => List())

  def askCompleteSymbolAt(p: Position, prefix: String, constructor: Boolean): List[SymbolInfoLight] = askOr({
    reloadSources(List(p.source))
    completeSymbolAt(p, prefix, constructor)
  }, t => List())

  def askCompleteMemberAt(p: Position, prefix: String): List[NamedTypeMemberInfoLight] = askOr({
    reloadSources(List(p.source))
    completeMemberAt(p, prefix)
  }, t => List())

  def askReloadAndTypeFiles(files: Iterable[SourceFile]) = askOr({
    reloadAndTypeFiles(files)
  }, t => ())

  def askClearTypeCache() = clearTypeCache

  def sourceFileForPath(path: String) = getSourceFile(path)

}

class RichPresentationCompiler(
  settings: Settings,
  reporter: Reporter,
  var parent: Actor,
  val config: ProjectConfig) extends Global(settings, reporter)
  with ModelBuilders with RichCompilerControl with RefactoringImpl {

  import Helpers._

  import analyzer.{ SearchResult, ImplicitSearch }

  private def typePublicMembers(tpe: Type): Iterable[TypeMember] = {
    val members = new LinkedHashMap[Symbol, TypeMember]
    def addTypeMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol) {
      try {
        val m = new TypeMember(
          sym,
          sym.tpe,
          sym.isPublic,
          inherited,
          viaView)
        members(sym) = m
      } catch { 
        case e => 
          System.err.println("Error: Omitting member " + sym
            + ": " + e)
      }
    }
    for (sym <- tpe.decls) {
      addTypeMember(sym, tpe, false, NoSymbol)
    }
    for (sym <- tpe.members) {
      addTypeMember(sym, tpe, true, NoSymbol)
    }
    members.values
  }

  private def getMembersForTypeAt(p: Position): Iterable[Member] = {
    typeAt(p) match {
      case Left(tpe) => {
        if (isNoParamArrowType(tpe)) {
          typePublicMembers(typeOrArrowTypeResult(tpe))
        } else {
          val members = try {
            typeMembers(p)
          } catch {
            case e => {
              System.err.println("Error retrieving type members:")
              e.printStackTrace(System.err)
              List()
            }
          }
          // Remove duplicates
          // Filter out synthetic things
          val bySym = new LinkedHashMap[Symbol, TypeMember]
          for (m <- (members ++ typePublicMembers(tpe))) {
            if (!m.sym.nameString.contains("$")) {
              bySym(m.sym) = m
            }
          }
          bySym.values
        }
      }
      case Right(e) => {
        System.err.println("ERROR: Failed to get any type information :(  " + e)
        List()
      }
    }
  }

  protected def inspectType(tpe: Type): TypeInspectInfo = {
    new TypeInspectInfo(
      TypeInfo(tpe),
      companionTypeOf(tpe).map(cacheType),
      prepareSortedInterfaceInfo(typePublicMembers(tpe.asInstanceOf[Type]))
      )
  }

  protected def inspectTypeAt(p: Position): TypeInspectInfo = {
    val members = getMembersForTypeAt(p)
    val preparedMembers = prepareSortedInterfaceInfo(members)
    typeAt(p) match {
      case Left(t) => new TypeInspectInfo(
        TypeInfo(t),
        companionTypeOf(t).map(cacheType),
        preparedMembers)
      case Right(_) => TypeInspectInfo.nullInfo
    }
  }

  private def typeOfTree(t: Tree): Either[Type, Throwable] = {
    var tree = t
    println("TREE CLASS: " + tree.getClass)
    tree = tree match {
      case Select(qual, name) if tree.tpe == ErrorType => {
        qual
      }
      case t: ImplDef if t.impl != null => {
        t.impl
      }
      case t: ValOrDefDef if t.tpt != null => {
        t.tpt
      }
      case t: ValOrDefDef if t.rhs != null => {
        t.rhs
      }
      case t => t
    }
    if (tree.tpe != null) {
      Left(tree.tpe)
    } else {
      Right(new Exception("Null tpe"))
    }
  }

  /*
    * Fall back to full typecheck if targeted fails
    * Removing this wrapper causes completion test failures.
    */
  def persistentTypedTreeAt(p: Position): Tree = {
    try {

      // typedTree may throw Fatal Error...
      val t = typedTreeAt(p)

      // don't return this tree unless it has a type..
      typeOfTree(t) match {
        case Left(_) => t
        case Right(e) => throw new FatalError(e.getMessage)
      }

    } catch {
      case e: FatalError =>
        {
          println("typedTreeAt threw FatalError: " + e + ", falling back to typedTree... ")
          typedTree(p.source, true)
          locateTree(p)
        }
    }
  }

  protected def typeAt(p: Position): Either[Type, Throwable] = {
    val tree = persistentTypedTreeAt(p)
    typeOfTree(tree)
  }

  protected def typeByName(name: String): Option[Type] = {
    def maybeType(sym: Symbol) = sym match {
      case NoSymbol => None
      case sym: Symbol => Some(sym.tpe)
      case _ => None
    }
    try {
      if (name.endsWith("$")) {
        maybeType(definitions.getModule(name.substring(0, name.length - 1)))
      } else {
        maybeType(definitions.getClass(name))
      }
    } catch {
      case e => None
    }
  }

  protected def symbolAt(p: Position): Either[Symbol, Throwable] = {
    p.source.file
    val tree = persistentTypedTreeAt(p)
    if (tree.symbol != null) {
      Left(tree.symbol)
    } else {
      Right(new Exception("Null sym"))
    }
  }

  /**
   * Override scopeMembers to fix issues with finding method params
   * and occasional exception in pre.memberType. Hopefully we can
   * get these changes into Scala.
   */
  override def scopeMembers(pos: Position): List[ScopeMember] = {
    persistentTypedTreeAt(pos) // to make sure context is entered
    val context = doLocateContext(pos)
    val locals = new LinkedHashMap[Name, ScopeMember]
    def addSymbol(sym: Symbol, pre: Type, viaImport: Tree) = {
      if (!sym.nameString.contains("$") &&
        !locals.contains(sym.name)) {
        try {
          val member = new ScopeMember(
            sym,
            sym.tpe,
            context.isAccessible(sym, pre, false),
            viaImport)
          locals(sym.name) = member
        } catch {
          case e => System.err.println("Error: Omitting scope member "
            + sym + ": " + e)
        }
      }
    }
    var cx = context
    while (cx != NoContext) {
      for (sym <- cx.scope) {
        addSymbol(sym, NoPrefix, EmptyTree)
      }
      if (cx.prefix != null) {
        for (sym <- cx.prefix.members) {
          addSymbol(sym, cx.prefix, EmptyTree)
        }
      }
      cx = cx.outer
    }
    for (imp <- context.imports) {
      val pre = imp.qual.tpe
      for (sym <- imp.allImportedSymbols) {
        addSymbol(sym, pre, imp.qual)
      }
    }
    val result = locals.values.toList
    result
  }

  protected def completePackageMember(path: String, prefix: String): Iterable[PackageMemberInfoLight] = {
    packageSymFromPath(path) match {
      case Some(sym) => {
        val memberSyms = packageMembers(sym).filterNot { s =>
          s == NoSymbol || s.nameString.contains("$")
        }
        memberSyms.flatMap { s =>
          val name = if (s.isPackage) { s.nameString } else { typeShortName(s) }
          if (name.startsWith(prefix)) {
            Some(new PackageMemberInfoLight(name))
          } else None
        }
      }
      case _ => List()
    }
  }

  protected def completeSymbolAt(p: Position, prefix: String, constructor: Boolean): List[SymbolInfoLight] = {
    val names = try {
      scopeMembers(p)
    } catch {
      case e => {
        System.err.println("Error retrieving scope members:")
        e.printStackTrace(System.err)
        List[ScopeMember]()
      }
    }
    val visibleNames = names.flatMap { m =>
      m match {
        case ScopeMember(sym, tpe, true, _) => {
          if (sym.nameString.startsWith(prefix)) {
            if (constructor) {
              SymbolInfoLight.constructorSynonyms(sym)
            } else {
              val synonyms = SymbolInfoLight.applySynonyms(sym)
              List(SymbolInfoLight(sym, tpe)) ++ synonyms
            }
          } else {
            List()
          }
        }
        case _ => List()
      }
    }.sortWith((a, b) => a.name.length <= b.name.length)
    visibleNames
  }

  protected def completeMemberAt(p: Position, prefix: String): List[NamedTypeMemberInfoLight] = {
    val members = getMembersForTypeAt(p)
    val visibleMembers = members.flatMap {
      case tm@TypeMember(sym, tpe, true, _, _) => {
        val s = sym.nameString
        if (s.startsWith(prefix) &&
          !(s == "this") &&
          !(s == "→")) {
          List(NamedTypeMemberInfoLight(tm))
        } else {
          List()
        }
      }
      case _ => List()
    }.toList.sortWith((a, b) => a.name.length <= b.name.length)
    visibleMembers
  }

  /**
   * Override so we send a notification to compiler actor when finished..
   */
  override def recompile(units: List[RichCompilationUnit]) {
    try {
      super.recompile(units)
      parent ! FullTypeCheckCompleteEvent()
    } catch {
      case e: FatalError => {
        // Cheesy hack to inform user that compiler 
        // thread crapped out. A new compiler thread
        // has already been created to replace it,
        // but will likely crap out again next time
        // a bg compiler runs.
        parent ! CompilerFatalError(e)
        throw e;
      }
    }

  }

  protected def reloadAndTypeFiles(sources: Iterable[SourceFile]) = {
    sources.foreach { s =>
      typedTree(s, true)
    }
  }

  override def askShutdown() {
    super.askShutdown()
    parent = null
  }

  override def finalize() {
    System.out.println("Finalizing Global instance.")
  }

}

