use std::path::Path;
use std::env;

const CPP_LIBRARY_FILE_NAME: &str = "libjonect_android_cpp.so";

fn main() {
    let rust_target = env::var("TARGET").unwrap();

    let cmake_arch = match rust_target.as_str() {
        "aarch64-linux-android" => "arm64-v8a",
        "armv7-linux-androideabi" => "armeabi-v7a",
        "i686-linux-android" => "x86",
        "x86_64-linux-android" => "x86_64",
        _ => return,
    };

    let profile = env::var("PROFILE").unwrap();
    match profile.as_str() {
        "release" | "debug"  => (),
        _ => return,
    };

    // Add Jonect's C++ code to link search path.

    // Current directory should be directory where this build script is located.
    let mut dir  = std::env::current_dir().unwrap();

    let path = format!("../app/build/intermediates/cmake/{}/obj/{}", profile, cmake_arch);
    dir.push(Path::new(&path));

    let mut dir = dir.canonicalize().unwrap();
    let dir_string = dir.to_str().unwrap().to_string();

    println!("cargo:rustc-link-search=native={dir_string}");

    // Create symbolic link to the library file. Rust Android Gradle plugin will
    // copy the library to the correct location when the symbolic link exists.

    dir.push(CPP_LIBRARY_FILE_NAME);
    let lib_file_path = dir;

    // Current directory should be directory where this build script is located.
    let mut symlink_path  = std::env::current_dir().unwrap();
    symlink_path.push(format!("target/{}/{}", rust_target, profile));
    let mut symlink_path = symlink_path.canonicalize().expect("ERROR");
    symlink_path.push(CPP_LIBRARY_FILE_NAME);

    if symlink_path.is_symlink() {
        if !symlink_path.exists() {
            panic!("Build '{}' using Android Studio or Gradle first.", lib_file_path.to_string_lossy());
        }
    } else if !symlink_path.exists() {
        std::os::unix::fs::symlink(lib_file_path, symlink_path).unwrap();
    }
}
