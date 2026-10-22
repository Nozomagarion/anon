"""Regenerate the checked-in Xcode project with Python's standard library only."""
from pathlib import Path
import hashlib
import json
import plistlib

ROOT = Path(__file__).resolve().parents[1]
objects = {}


class Ref(str):
    pass


def add(key, isa, **values):
    ref = Ref(hashlib.sha1(key.encode()).hexdigest()[:24].upper())
    objects[ref] = {"isa": isa, **values}
    return ref


def text(value, depth=0):
    if isinstance(value, Ref):
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list):
        return "( " + ", ".join(text(v, depth + 1) for v in value) + ", )" if value else "()"
    indent = "\t" * depth
    return "{\n" + "".join(f"{indent}\t{k} = {text(v, depth + 1)};\n" for k, v in value.items()) + indent + "}"


def file(path, kind):
    assert (ROOT / path).exists(), path
    return add("file:" + path, "PBXFileReference", lastKnownFileType=kind, path=path, sourceTree="SOURCE_ROOT")


def phase(name, isa, refs):
    build_files = [add(name + ":" + r, "PBXBuildFile", fileRef=r) for r in refs]
    return add(name, isa, buildActionMask=2147483647, files=build_files, runOnlyForDeploymentPostprocessing=0)


def configs(name, settings):
    entries = []
    for variant in ["Debug", "Release"]:
        entry = dict(settings)
        entry.update({"SWIFT_OPTIMIZATION_LEVEL": "-Onone" if variant == "Debug" else "-O"})
        if variant == "Debug":
            entry.update({"DEBUG_INFORMATION_FORMAT": "dwarf", "ENABLE_TESTABILITY": "YES",
                          "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG", "ONLY_ACTIVE_ARCH": "YES"})
        else:
            entry.update({"DEBUG_INFORMATION_FORMAT": "dwarf-with-dsym", "SWIFT_COMPILATION_MODE": "wholemodule"})
        entries.append(add(name + variant, "XCBuildConfiguration", name=variant, buildSettings=entry))
    return add(name + "configs", "XCConfigurationList", buildConfigurations=entries,
               defaultConfigurationIsVisible=0, defaultConfigurationName="Release")


app_sources = [file(str(p.relative_to(ROOT)).replace("\\", "/"), "sourcecode.swift")
               for base in [ROOT / "ANON", ROOT / "ANONCore/Sources/ANONCore"] for p in sorted(base.rglob("*.swift"))]
test_sources = [file(str(p.relative_to(ROOT)).replace("\\", "/"), "sourcecode.swift")
                for base in [ROOT / "ANONTests", ROOT / "ANONCore/Tests/ANONCoreTests"] for p in sorted(base.rglob("*.swift"))]
app_resources = [file("ANON/Resources/faces.jpg", "image.jpeg"),
                 file("ANON/Resources/Assets.xcassets", "folder.assetcatalog"),
                 file("ANON/PrivacyInfo.xcprivacy", "text.xml")]
test_resources = [file("ANONTests/Fixtures/faces-portrait.mp4", "video.mp4")]
info = file("ANON/Info.plist", "text.plist.xml")
app_product = add("app-product", "PBXFileReference", explicitFileType="wrapper.application", path="ANON.app", sourceTree="BUILT_PRODUCTS_DIR")
test_product = add("test-product", "PBXFileReference", explicitFileType="wrapper.cfbundle", path="ANONTests.xctest", sourceTree="BUILT_PRODUCTS_DIR")
products = add("products", "PBXGroup", name="Products", children=[app_product, test_product], sourceTree="<group>")
app_group = add("app-group", "PBXGroup", name="ANON", children=app_sources + app_resources + [info], sourceTree="<group>")
test_group = add("test-group", "PBXGroup", name="Tests", children=test_sources + test_resources, sourceTree="<group>")
main = add("main-group", "PBXGroup", children=[app_group, test_group, products], sourceTree="<group>")
common = {
    "IPHONEOS_DEPLOYMENT_TARGET": "16.0", "SDKROOT": "iphoneos", "SWIFT_VERSION": "5.0",
    "CLANG_ENABLE_MODULES": "YES", "CLANG_ENABLE_OBJC_ARC": "YES",
    "GCC_C_LANGUAGE_STANDARD": "gnu17", "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
}
app_settings = {
    "PRODUCT_BUNDLE_IDENTIFIER": "fr.anon.app.ios", "PRODUCT_NAME": "$(TARGET_NAME)",
    "INFOPLIST_FILE": "ANON/Info.plist", "GENERATE_INFOPLIST_FILE": "NO",
    "MARKETING_VERSION": "0.1.1", "CURRENT_PROJECT_VERSION": "1", "CODE_SIGN_STYLE": "Automatic",
    "TARGETED_DEVICE_FAMILY": "1,2", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator",
    "SUPPORTS_MACCATALYST": "NO", "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
    "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
    "SWIFT_STRICT_CONCURRENCY": "targeted",
}
app = add("app", "PBXNativeTarget", name="ANON", productName="ANON", productReference=app_product,
          productType="com.apple.product-type.application", buildConfigurationList=configs("app", app_settings),
          buildPhases=[phase("app-sources", "PBXSourcesBuildPhase", app_sources),
                       phase("app-frameworks", "PBXFrameworksBuildPhase", []),
                       phase("app-resources", "PBXResourcesBuildPhase", app_resources)], buildRules=[], dependencies=[])
project_ref = Ref(hashlib.sha1(b"project").hexdigest()[:24].upper())
proxy = add("test-proxy", "PBXContainerItemProxy", containerPortal=project_ref, proxyType=1, remoteGlobalIDString=app, remoteInfo="ANON")
dependency = add("test-dependency", "PBXTargetDependency", target=app, targetProxy=proxy)
test_settings = {
    "PRODUCT_BUNDLE_IDENTIFIER": "fr.anon.app.ios.tests", "PRODUCT_NAME": "$(TARGET_NAME)",
    "GENERATE_INFOPLIST_FILE": "YES", "CODE_SIGN_STYLE": "Automatic", "TARGETED_DEVICE_FAMILY": "1,2",
    "BUNDLE_LOADER": "$(TEST_HOST)", "TEST_HOST": "$(BUILT_PRODUCTS_DIR)/ANON.app/ANON",
    "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks"],
}
tests = add("tests", "PBXNativeTarget", name="ANONTests", productName="ANONTests", productReference=test_product,
            productType="com.apple.product-type.bundle.unit-test", buildConfigurationList=configs("tests", test_settings),
            buildPhases=[phase("test-sources", "PBXSourcesBuildPhase", test_sources),
                         phase("test-frameworks", "PBXFrameworksBuildPhase", []),
                         phase("test-resources", "PBXResourcesBuildPhase", test_resources)], buildRules=[], dependencies=[dependency])
project = add("project", "PBXProject", attributes={"BuildIndependentTargetsInParallel": "YES", "LastUpgradeCheck": "1600",
    "TargetAttributes": {app: {"CreatedOnToolsVersion": "16.0"}, tests: {"CreatedOnToolsVersion": "16.0", "TestTargetID": app}}},
    buildConfigurationList=configs("project", common), compatibilityVersion="Xcode 14.0", developmentRegion="fr",
    hasScannedForEncodings=0, knownRegions=["fr", "en", "Base"], mainGroup=main, productRefGroup=products,
    projectDirPath="", projectRoot="", targets=[app, tests])
project_dir = ROOT / "ANON.xcodeproj"
project_dir.mkdir(exist_ok=True)
(project_dir / "project.pbxproj").write_text("// !$*UTF8*$!\n" + text({"archiveVersion": 1, "classes": {}, "objectVersion": 56, "objects": objects, "rootObject": project}) + "\n", encoding="utf-8")
scheme_dir = project_dir / "xcshareddata/xcschemes"
scheme_dir.mkdir(parents=True, exist_ok=True)


def buildable(identifier, name, product):
    return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" BuildableName="{product}" BlueprintName="{name}" ReferencedContainer="container:ANON.xcodeproj"/>'


app_ref = buildable(app, "ANON", "ANON.app")
test_ref = buildable(tests, "ANONTests", "ANONTests.xctest")
(scheme_dir / "ANON.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.7">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
    <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app_ref}</BuildActionEntry>
  </BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES">
    <Testables><TestableReference skipped="NO" parallelizable="NO">{test_ref}</TestableReference></Testables>
  </TestAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''', encoding="utf-8")
for path in [ROOT / "ANON/Info.plist", ROOT / "ANON/PrivacyInfo.xcprivacy"]:
    plistlib.loads(path.read_bytes())
print(f"Xcode project: {len(app_sources)} app sources, {len(test_sources)} test sources")
