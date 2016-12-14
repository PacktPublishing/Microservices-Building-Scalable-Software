/*
Hello World module
*/

#include <iostream>

#include "test_module.hpp"

class TestModuleImpl : public TestModule

{

public:

  TestModuleImpl()

  {

    std::cout << "HelloWorld!" << std::endl;

  }

  virtual int foo(char a, long b)

  {

    return a + b;

  }

  virtual int bar(float a, double b)

  {

    return a * b;

  }

};

static TestModule* create()

{

    return new TestModule();

}

static bool compatible()

{

  return true;

}
